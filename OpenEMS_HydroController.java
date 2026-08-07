package io.openems.edge.controller.hydro.inline;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;

/**
 * Scalar Coherence Controller for Inline Hydro Turbines (PATs) in Water Supply Networks.
 *
 * Implements Jain & Khare (2024) optimization principles through scalar field governance:
 * - Turbines harvest excess pressure scalar density
 * - Minimum service pressure is sacred (Federation Rule 3)
 * - Leakage reduction is a coherence side-effect, not the primary goal
 *
 * The network is a scalar potential field φ(x). Flow is the gradient ∇φ.
 * The turbine is a scalar extraction point that maintains φ ≥ φ_min everywhere.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Hydro.Inline", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class InlineHydroController extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        // Inputs — scalar field sensors
        NETWORK_PRESSURE_KPA(Doc.of(OpenemsType.INTEGER).unit(Unit.PASCAL).text("Network pressure at turbine inlet")),
        NETWORK_FLOW_LPS(Doc.of(OpenemsType.INTEGER).unit(Unit.CUBIC_METER_PER_HOUR).text("Network flow rate")),
        TURBINE_RPM(Doc.of(OpenemsType.INTEGER).unit(Unit.NONE).text("Turbine rotational speed")),
        TURBINE_POWER_W(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).text("Turbine electrical output")),
        DOWNSTREAM_PRESSURE_KPA(Doc.of(OpenemsType.INTEGER).unit(Unit.PASCAL).text("Pressure at critical downstream node")),
        LEAKAGE_RATE_LPS(Doc.of(OpenemsType.INTEGER).unit(Unit.CUBIC_METER_PER_HOUR).text("Estimated leakage rate")),

        // Outputs — control signals
        TURBINE_THROTTLE_PCT(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT).text("Turbine throttle valve 0–100%")),
        BYPASS_VALVE(Doc.of(OpenemsType.BOOLEAN).text("Bypass valve — true when turbine offline")),
        RECTIFIER_ENABLE(Doc.of(OpenemsType.BOOLEAN).text("DC rectifier enable")),

        // Scalar coherence metrics
        PRESSURE_SCALAR_MARGIN(Doc.of(OpenemsType.INTEGER).unit(Unit.PASCAL).text("Pressure above minimum service head")),
        SCALAR_EXTRACTION_RATE(Doc.of(OpenemsType.FLOAT).text("Fraction of harvestable scalar density being extracted")),
        DAILY_ENERGY_KWH(Doc.of(OpenemsType.INTEGER).unit(Unit.KILOWATT_HOURS).text("Daily energy generated")),
        DAILY_LEAKAGE_PREVENTED_L(Doc.of(OpenemsType.INTEGER).unit(Unit.CUBIC_METER).text("Estimated daily leakage prevented")),

        // Coherence state
        HYDRO_COHERENCE_STATE(Doc.of(HydroCoherenceState.values()).text("Hydro subsystem coherence state")),
        STATE_MACHINE(Doc.of(State.values()).text("Controller state machine"));

        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }

    public enum HydroCoherenceState {
        FULL_EXTRACTION,      // Pressure well above minimum — harvesting maximum
        MODERATE_EXTRACTION,  // Pressure above minimum with margin — harvesting safely
        PRESSURE_FLOOR_GUARD, // Pressure near minimum — throttling to protect service
        BYPASS_MODE,          // Pressure below minimum — turbine offline, bypass open
        LEAKAGE_SUPPRESSION,  // Pressure elevated due to low demand — extracting to reduce leakage
        EMERGENCY_SHUTOFF     // Fault condition — all valves closed
    }

    public enum State {
        UNDEFINED, EXTRACTING, THROTTLING, BYPASS, ERROR, SHUTOFF
    }

    private final Logger log = LoggerFactory.getLogger(InlineHydroController.class);

    @Reference
    private ComponentManager componentManager;

    @Reference
    private ManagedSymmetricEss ess;

    private Config config;

    // PID state for smooth throttle control
    private float integralError = 0.0f;
    private float lastError = 0.0f;

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.log.info("Inline Hydro Scalar Controller activated. Service pressure floor: {} kPa", config.minServicePressureKpa());
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
        // Safe shutdown: open bypass, close turbine
        try {
            this.setBypassValve(true);
            this.setThrottle(0);
            this.setRectifierEnable(false);
        } catch (OpenemsNamedException e) {
            this.log.error("Failed safe shutdown of hydro controller", e);
        }
    }

    @Override
    public void run() throws OpenemsNamedException {
        // ── 1. READ SCALAR FIELD STATE ─────────────────────────────────────
        int pressureKpa = this.getNetworkPressure();
        int downstreamPressureKpa = this.getDownstreamPressure();
        int flowLps = this.getNetworkFlow();
        int turbinePowerW = this.getTurbinePower();
        int leakageLps = this.getLeakageRate();

        int minServiceKpa = config.minServicePressureKpa();
        int safetyMarginKpa = config.pressureSafetyMarginKpa();
        int criticalPressure = Math.min(pressureKpa, downstreamPressureKpa);

        // ── 2. COMPUTE SCALAR MARGIN ──────────────────────────────────────
        int scalarMargin = criticalPressure - minServiceKpa;
        this.channel(ChannelId.PRESSURE_SCALAR_MARGIN).setNextValue(scalarMargin);

        // ── 3. COHERENCE GATE LOGIC ───────────────────────────────────────
        HydroCoherenceState coherenceState;
        int throttlePct;
        boolean bypassOpen;
        boolean rectifierOn;
        State state;

        if (criticalPressure < minServiceKpa - safetyMarginKpa) {
            // CRITICAL: Pressure below service floor — turbine OFF, bypass ON
            coherenceState = HydroCoherenceState.BYPASS_MODE;
            throttlePct = 0;
            bypassOpen = true;
            rectifierOn = false;
            state = State.BYPASS;
            this.integralError = 0; // Reset PID

        } else if (criticalPressure < minServiceKpa) {
            // WARNING: Pressure at or below minimum — aggressive throttle reduction
            coherenceState = HydroCoherenceState.PRESSURE_FLOOR_GUARD;
            // Linear ramp from 0% throttle at minService to 30% at minService + margin
            throttlePct = (int) (30.0f * (criticalPressure - minServiceKpa + safetyMarginKpa) / safetyMarginKpa);
            throttlePct = Math.max(0, Math.min(30, throttlePct));
            bypassOpen = false;
            rectifierOn = throttlePct > 5;
            state = State.THROTTLING;

        } else if (scalarMargin < safetyMarginKpa) {
            // CAUTIOUS: Small margin — moderate extraction
            coherenceState = HydroCoherenceState.MODERATE_EXTRACTION;
            // Map margin [0, safetyMargin] → throttle [30%, 70%]
            throttlePct = 30 + (int) (40.0f * scalarMargin / safetyMarginKpa);
            throttlePct = Math.max(30, Math.min(70, throttlePct));
            bypassOpen = false;
            rectifierOn = true;
            state = State.EXTRACTING;

        } else {
            // ABUNDANT: Pressure well above minimum — full extraction
            // But respect maximum turbine capacity
            coherenceState = HydroCoherenceState.FULL_EXTRACTION;
            int maxThrottle = config.maxThrottlePercent();
            // If leakage is high, increase extraction (leakage suppression)
            if (leakageLps > config.leakageThresholdLps()) {
                coherenceState = HydroCoherenceState.LEAKAGE_SUPPRESSION;
                maxThrottle = Math.min(100, maxThrottle + 10);
            }
            throttlePct = maxThrottle;
            bypassOpen = false;
            rectifierOn = true;
            state = State.EXTRACTING;
        }

        // ── 4. PID SMOOTHING (anti-hunting) ───────────────────────────────
        throttlePct = this.applyPidSmoothing(throttlePct, scalarMargin);

        // ── 5. EXECUTE CONTROL ────────────────────────────────────────────
        this.setThrottle(throttlePct);
        this.setBypassValve(bypassOpen);
        this.setRectifierEnable(rectifierOn);

        this.channel(ChannelId.TURBINE_THROTTLE_PCT).setNextValue(throttlePct);
        this.channel(ChannelId.BYPASS_VALVE).setNextValue(bypassOpen);
        this.channel(ChannelId.RECTIFIER_ENABLE).setNextValue(rectifierOn);
        this.channel(ChannelId.HYDRO_COHERENCE_STATE).setNextValue(coherenceState);
        this.channel(ChannelId.STATE_MACHINE).setNextValue(state);

        // Scalar extraction rate: how much of available margin are we using?
        float extractionRate = scalarMargin > 0 ? (float) throttlePct / 100.0f : 0.0f;
        this.channel(ChannelId.SCALAR_EXTRACTION_RATE).setNextValue(extractionRate);

        // ── 6. ENERGY & LEAKAGE ACCOUNTING ────────────────────────────────
        this.accumulateDailyEnergy(turbinePowerW);
        this.estimateLeakagePrevented(leakageLps, scalarMargin);

        // ── 7. LOGGING ────────────────────────────────────────────────────
        if (this.log.isDebugEnabled()) {
            this.log.debug("Hydro P={}kPa margin={}kPa | Throttle={}% | State={} | Power={}W | Leakage={}L/h",
                pressureKpa, scalarMargin, throttlePct, coherenceState, turbinePowerW, leakageLps);
        }
    }

    // ── PID SMOOTHING ───────────────────────────────────────────────────
    private int applyPidSmoothing(int targetThrottle, int scalarMargin) {
        float Kp = config.pidKp();
        float Ki = config.pidKi();
        float Kd = config.pidKd();

        float error = scalarMargin; // We want margin > 0
        this.integralError += error * 0.1f; // 100ms cycle assumption
        this.integralError = Math.max(-100, Math.min(100, this.integralError)); // Anti-windup
        float derivative = (error - this.lastError) / 0.1f;
        this.lastError = error;

        float pidOutput = Kp * error + Ki * this.integralError + Kd * derivative;
        // pidOutput is a pressure correction; map to throttle adjustment
        int adjustment = (int) (pidOutput * 0.5f);
        return Math.max(0, Math.min(100, targetThrottle + adjustment));
    }

    // ── HARDWARE INTERFACE STUBS ────────────────────────────────────────
    private void setThrottle(int pct) throws OpenemsNamedException {
        // In production: write to Modbus register on valve actuator
        // or PWM output to servo
    }

    private void setBypassValve(boolean open) throws OpenemsNamedException {
        // In production: write to digital output
    }

    private void setRectifierEnable(boolean enable) throws OpenemsNamedException {
        // In production: enable DC rectifier/MPPT for PAT generator
    }

    private int getNetworkPressure() {
        return this.channel(ChannelId.NETWORK_PRESSURE_KPA).value().orElse(300);
    }

    private int getDownstreamPressure() {
        return this.channel(ChannelId.DOWNSTREAM_PRESSURE_KPA).value().orElse(250);
    }

    private int getNetworkFlow() {
        return this.channel(ChannelId.NETWORK_FLOW_LPS).value().orElse(10);
    }

    private int getTurbinePower() {
        return this.channel(ChannelId.TURBINE_POWER_W).value().orElse(0);
    }

    private int getLeakageRate() {
        return this.channel(ChannelId.LEAKAGE_RATE_LPS).value().orElse(0);
    }

    private void accumulateDailyEnergy(int powerW) {
        // In production: integrate power over time, reset at midnight
        int current = this.channel(ChannelId.DAILY_ENERGY_KWH).value().orElse(0);
        this.channel(ChannelId.DAILY_ENERGY_KWH).setNextValue(current + powerW / 1000);
    }

    private void estimateLeakagePrevented(int leakageLps, int scalarMargin) {
        // Leakage reduction proportional to pressure reduction
        // Simplified: if we reduce pressure by ΔP, leakage reduces by ~ΔP^1.15
        // For logging/accounting only
        int prevented = (int) (leakageLps * 0.1f * scalarMargin / 100.0f);
        int current = this.channel(ChannelId.DAILY_LEAKAGE_PREVENTED_L).value().orElse(0);
        this.channel(ChannelId.DAILY_LEAKAGE_PREVENTED_L).setNextValue(current + prevented);
    }
}
