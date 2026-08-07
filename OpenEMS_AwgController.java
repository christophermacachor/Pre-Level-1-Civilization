package io.openems.edge.controller.water.awg;

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
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.io.api.DigitalOutput;

/**
 * Scalar Coherence Controller for Atmospheric Water Generator (AWG).
 *
 * Implements the Macachor Density Differential Absolute:
 * AWG operates only when ambient scalar conditions (humidity, temperature)
 * support condensation AND the battery maintains sovereign charge levels.
 *
 * Federation Rule 2: Water is harvested from excess scalar density,
 * not stolen from storage.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Water.Awg", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class WaterAwgController extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        // Inputs — scalar field sensors
        AMBIENT_HUMIDITY(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT).text("Ambient relative humidity")),
        AMBIENT_TEMPERATURE(Doc.of(OpenemsType.INTEGER).unit(Unit.DECIDEGREE_CELSIUS).text("Ambient temperature")),
        BATTERY_SOC(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT).text("Battery state of charge")),
        SOLAR_PRODUCTION(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).text("Current solar production")),
        BASE_LOAD(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).text("Estimated base load")),

        // Outputs — control signals
        AWG_ENABLE(Doc.of(OpenemsType.BOOLEAN).text("AWG power relay command")),
        AWG_VOLTAGE_SETPOINT(Doc.of(OpenemsType.INTEGER).unit(Unit.MILLIVOLT).text("Buck converter setpoint (mV)")),
        AWG_POWER_ESTIMATE(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).text("Estimated AWG power draw")),

        // Scalar coherence metrics
        SCALAR_EQUILIBRIUM_INDEX(Doc.of(OpenemsType.FLOAT).text("ψ(t) — scalar equilibrium index 0.0–1.0")),
        COHERENCE_STATE(Doc.of(CoherenceState.values()).text("Current coherence state of AWG subsystem")),
        DAILY_WATER_OUTPUT_ML(Doc.of(OpenemsType.INTEGER).unit(Unit.CUBIC_METER).text("Accumulated daily water output (mL)")),

        // Alarms
        STATE_MACHINE(Doc.of(State.values()).text("Controller state machine state"));

        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }

    public enum CoherenceState {
        COHERENT,           // All conditions met — AWG running optimally
        DEGRADED_HUMIDITY,  // Humidity below threshold — standby
        DEGRADED_TEMP,      // Temperature below threshold — standby
        BATTERY_SOVEREIGNTY_VIOLATION, // SOC below sovereign floor — locked out
        EXCESS_DENSITY_HARVEST, // Running at maximum COP — scalar bounty
        STANDBY             // Idle awaiting coherence restoration
    }

    public enum State {
        UNDEFINED, RUNNING, STANDBY, ERROR
    }

    private final Logger log = LoggerFactory.getLogger(WaterAwgController.class);

    @Reference
    private ComponentManager componentManager;

    @Reference
    private ManagedSymmetricEss ess;

    @Reference
    private DigitalOutput awgRelay;

    private Config config;

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.log.info("AWG Scalar Controller activated. Sovereign SOC floor: {}%", config.sovereignSocFloor());
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
        // Ensure safe shutdown — disable AWG
        try {
            this.awgRelay.setOutput(false);
        } catch (OpenemsNamedException e) {
            this.log.error("Failed to disable AWG on deactivate", e);
        }
    }

    @Override
    public void run() throws OpenemsNamedException {
        // ── 1. READ SCALAR FIELD STATE ─────────────────────────────────────
        int humidity = this.getAmbientHumidity();
        int tempDeciC = this.getAmbientTemperature();
        int soc = this.getBatterySoc();
        int solarProduction = this.getSolarProduction();
        int baseLoad = this.config.baseLoadEstimateW();

        // ── 2. COMPUTE SCALAR EQUILIBRIUM INDEX ψ(t) ──────────────────────
        // ψ = f(humidity, temp, soc, excess_generation)
        // Each factor normalized 0–1, weighted by sovereignty priority
        float psiHumidity = normalize(humidity, config.minHumidity(), 100);
        float psiTemp = normalize(tempDeciC, config.minTempDeciC(), 350); // 35°C max
        float psiSoc = normalize(soc, config.sovereignSocFloor(), 100);
        int excessPower = solarProduction - baseLoad - config.batteryChargePriorityW();
        float psiExcess = excessPower > 0 ? Math.min(1.0f, excessPower / (float) config.awgPowerW()) : 0.0f;

        // Sovereignty-weighted equilibrium: SOC is sacred (0.4 weight)
        float psi = 0.4f * psiSoc + 0.25f * psiHumidity + 0.15f * psiTemp + 0.2f * psiExcess;
        psi = Math.max(0.0f, Math.min(1.0f, psi));

        this.channel(ChannelId.SCALAR_EQUILIBRIUM_INDEX).setNextValue(psi);

        // ── 3. COHERENCE GATE LOGIC ───────────────────────────────────────
        CoherenceState coherenceState;
        boolean shouldEnable;
        int voltageSetpoint;

        if (soc < config.sovereignSocFloor()) {
            // FEDERATION RULE 1: Battery sovereignty is absolute
            coherenceState = CoherenceState.BATTERY_SOVEREIGNTY_VIOLATION;
            shouldEnable = false;
            voltageSetpoint = 0;
        } else if (humidity < config.minHumidity()) {
            coherenceState = CoherenceState.DEGRADED_HUMIDITY;
            shouldEnable = false;
            voltageSetpoint = 0;
        } else if (tempDeciC < config.minTempDeciC()) {
            coherenceState = CoherenceState.DEGRADED_TEMP;
            shouldEnable = false;
            voltageSetpoint = 0;
        } else if (psi > 0.85f && excessPower > config.awgPowerW()) {
            // Scalar bounty — excess density available
            coherenceState = CoherenceState.EXCESS_DENSITY_HARVEST;
            shouldEnable = true;
            voltageSetpoint = config.optimalVoltageMv(); // 9500 mV = 9.5V for max COP
        } else if (psi > 0.6f) {
            coherenceState = CoherenceState.COHERENT;
            shouldEnable = true;
            voltageSetpoint = config.optimalVoltageMv();
        } else {
            coherenceState = CoherenceState.STANDBY;
            shouldEnable = false;
            voltageSetpoint = 0;
        }

        // ── 4. EXECUTE CONTROL ────────────────────────────────────────────
        this.channel(ChannelId.COHERENCE_STATE).setNextValue(coherenceState);
        this.channel(ChannelId.AWG_ENABLE).setNextValue(shouldEnable);
        this.channel(ChannelId.AWG_VOLTAGE_SETPOINT).setNextValue(voltageSetpoint);
        this.channel(ChannelId.AWG_POWER_ESTIMATE).setNextValue(
            shouldEnable ? config.awgPowerW() : 0
        );

        this.awgRelay.setOutput(shouldEnable);

        // ── 5. STATE MACHINE ──────────────────────────────────────────────
        State state = shouldEnable ? State.RUNNING : State.STANDBY;
        this.channel(ChannelId.STATE_MACHINE).setNextValue(state);

        // ── 6. LOGGING ────────────────────────────────────────────────────
        if (this.log.isDebugEnabled()) {
            this.log.debug("AWG ψ={:.2f} | State={} | Humidity={}% | Temp={}°C | SOC={}% | Enable={}",
                psi, coherenceState, humidity, tempDeciC / 10.0, soc, shouldEnable);
        }
    }

    // ── HELPER METHODS ──────────────────────────────────────────────────
    private int getAmbientHumidity() {
        // In production: read from DHT22 via OneWire or MQTT weather bridge
        // For now: read from configured channel or fallback
        return this.channel(ChannelId.AMBIENT_HUMIDITY).value().orElse(50);
    }

    private int getAmbientTemperature() {
        return this.channel(ChannelId.AMBIENT_TEMPERATURE).value().orElse(250); // 25°C default
    }

    private int getBatterySoc() {
        return this.ess.getSoc().value().orElse(50);
    }

    private int getSolarProduction() {
        return this.channel(ChannelId.SOLAR_PRODUCTION).value().orElse(0);
    }

    private static float normalize(int value, int min, int max) {
        if (value <= min) return 0.0f;
        if (value >= max) return 1.0f;
        return (float) (value - min) / (float) (max - min);
    }
}
