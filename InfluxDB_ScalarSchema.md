[InfluxDB_ScalarSchema.md](https://github.com/user-attachments/files/30813577/InfluxDB_ScalarSchema.md)
# Scalar Field Time-Series Schema for OpenEMS Federation Nodes
# InfluxDB 2.x — Bucket: `federation_scalar`
# Retention: 90 days hot, 1 year warm, indefinite cold (downsampled)

# ─────────────────────────────────────────────────────────────
# MEASUREMENT: scalar_field_state
# The canonical scalar field snapshot. One point per second per node.
# ─────────────────────────────────────────────────────────────

measurement scalar_field_state
    # Tags (indexed, low cardinality)
    tag node_id         # Federation node identifier (e.g., "node-alpha-01")
    tag region          # Geographic/climate region
    tag grid_mode       # "island" | "grid_tied" | "grid_optional"

    # Fields (numeric, high cardinality)
    field psi           float   # Scalar equilibrium index ψ(t) ∈ [0.0, 1.0]
    field psi_solar     float   # Solar density contribution to ψ
    field psi_wind      float   # Wind density contribution to ψ
    field psi_hydro     float   # Hydro pressure contribution to ψ
    field psi_battery   float   # Battery SOC contribution to ψ
    field psi_water     float   # Water availability contribution to ψ

    field solar_w       int     # Solar generation (W)
    field wind_w        int     # Wind generation (W)
    field hydro_w       int     # Inline hydro generation (W)
    field load_w        int     # Total load (W)
    field battery_soc   int     # Battery state of charge (%)
    field battery_w     int     # Battery charge/discharge (+/- W)

    field pressure_kpa  int     # Network pressure at turbine (kPa)
    field flow_lph      int     # Network flow (L/h)
    field leakage_lph   int     # Estimated leakage (L/h)

    field humidity_pct  int     # Ambient humidity (%)
    field temp_c        float   # Ambient temperature (°C)
    field awg_ml_h      int     # AWG water output (mL/h)
    field awg_state     string  # AWG coherence state
    field hydro_state   string  # Hydro coherence state

# ─────────────────────────────────────────────────────────────
# MEASUREMENT: coherence_events
# Discrete events — state transitions, alarms, governance decisions
# ─────────────────────────────────────────────────────────────

measurement coherence_events
    tag node_id
    tag event_type      # "awg_enable" | "awg_disable" | "hydro_throttle" | 
                        # "battery_sovereignty_violation" | "leakage_spike" | 
                        # "grid_arbitrage" | "forecast_update"
    tag severity        # "info" | "warning" | "critical"

    field message       string
    field old_value     float
    field new_value     float
    field decision_rationale string  # Human-readable AI governance reasoning

# ─────────────────────────────────────────────────────────────
# MEASUREMENT: forecast_vectors
# LSTM predictions — 24h ahead, updated every hour
# ─────────────────────────────────────────────────────────────

measurement forecast_vectors
    tag node_id
    tag horizon_h       # "1" | "6" | "12" | "24"

    field solar_pred_w      int     # Predicted solar (W)
    field wind_pred_w       int     # Predicted wind (W)
    field load_pred_w       int     # Predicted load (W)
    field hydro_pred_w      int     # Predicted hydro (W)
    field psi_pred          float   # Predicted equilibrium index
    field confidence        float   # Model confidence ∈ [0.0, 1.0]
    field model_version     string  # LSTM checkpoint identifier

# ─────────────────────────────────────────────────────────────
# MEASUREMENT: governance_decisions
# Audit trail for AI governance — immutable, append-only
# ─────────────────────────────────────────────────────────────

measurement governance_decisions
    tag node_id
    tag rule_id         # "RULE_1_BATTERY" | "RULE_2_WATER" | "RULE_3_HYDRO" | 
                        # "RULE_4_LEAKAGE" | "RULE_5_GRID" | "RULE_6_FORECAST"
    tag decision        # "allow" | "deny" | "throttle" | "shed" | "arbitrage"

    field trigger_value     float
    field threshold         float
    field action_taken      string
    field expected_outcome  string
    field actual_outcome    string
    field psi_before        float
    field psi_after         float

# ─────────────────────────────────────────────────────────────
# MEASUREMENT: device_telemetry
# Raw device data — kept for 7 days, then downsampled
# ─────────────────────────────────────────────────────────────

measurement device_telemetry
    tag node_id
    tag device_id       # "libre_solar_mppt_01" | "awg_peltier_01" | 
                        # "pat_turbine_01" | "bms_pylontech_01"
    tag device_type     # "mppt" | "bms" | "inverter" | "sensor" | "actuator"

    field voltage_v     float
    field current_a     float
    field power_w       int
    field temperature_c float
    field status_code   int
    field error_flags   int

# ═════════════════════════════════════════════════════════════
# FLUX QUERIES — Federation Operations
# ═════════════════════════════════════════════════════════════

# ── Query 1: Real-Time Scalar Field Dashboard ────────────────
from(bucket: "federation_scalar")
    |> range(start: -1m)
    |> filter(fn: (r) => r._measurement == "scalar_field_state")
    |> filter(fn: (r) => r.node_id == "node-alpha-01")
    |> filter(fn: (r) => r._field =~ /^(psi|solar_w|wind_w|hydro_w|battery_soc|awg_ml_h|pressure_kpa|leakage_lph)$/)
    |> last()
    |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")

# ── Query 2: 24h Scalar Equilibrium Trend ────────────────────
from(bucket: "federation_scalar")
    |> range(start: -24h)
    |> filter(fn: (r) => r._measurement == "scalar_field_state")
    |> filter(fn: (r) => r._field == "psi")
    |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)

# ── Query 3: Leakage vs Hydro Extraction Correlation ─────────
from(bucket: "federation_scalar")
    |> range(start: -7d)
    |> filter(fn: (r) => r._measurement == "scalar_field_state")
    |> filter(fn: (r) => r._field == "leakage_lph" or r._field == "hydro_w")
    |> aggregateWindow(every: 1h, fn: mean)
    |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
    |> map(fn: (r) => ({ r with correlation: r.hydro_w / (r.leakage_lph + 1) }))

# ── Query 4: AI Governance Decision Audit ────────────────────
from(bucket: "federation_scalar")
    |> range(start: -30d)
    |> filter(fn: (r) => r._measurement == "governance_decisions")
    |> filter(fn: (r) => r.node_id == "node-alpha-01")
    |> filter(fn: (r) => r._field == "psi_before" or r._field == "psi_after")
    |> pivot(rowKey:["_time", "rule_id", "decision"], columnKey: ["_field"], valueColumn: "_value")
    |> map(fn: (r) => ({ r with psi_delta: r.psi_after - r.psi_before }))

# ── Query 5: Forecast Accuracy Validation ────────────────────
from(bucket: "federation_scalar")
    |> range(start: -7d)
    |> filter(fn: (r) => r._measurement == "forecast_vectors" or r._measurement == "scalar_field_state")
    |> filter(fn: (r) => r._field == "solar_pred_w" or r._field == "solar_w")
    |> aggregateWindow(every: 1h, fn: mean)
    |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
    |> map(fn: (r) => ({ r with error_pct: 100.0 * math.abs(x: r.solar_pred_w - r.solar_w) / (r.solar_w + 1) }))

# ═════════════════════════════════════════════════════════════
# RETENTION POLICIES
# ═════════════════════════════════════════════════════════════

# Hot (raw, 1s resolution): 90 days
# Warm (1m aggregates): 1 year
# Cold (1h aggregates): indefinite

# Task: Downsample raw → 1m
option task = {name: "downsample_scalar_1m", every: 1m}
from(bucket: "federation_scalar")
    |> range(start: -task.every)
    |> filter(fn: (r) => r._measurement == "scalar_field_state")
    |> aggregateWindow(every: 1m, fn: mean)
    |> to(bucket: "federation_scalar_1m")

# Task: Downsample 1m → 1h
option task = {name: "downsample_scalar_1h", every: 1h}
from(bucket: "federation_scalar_1m")
    |> range(start: -task.every)
    |> aggregateWindow(every: 1h, fn: mean)
    |> to(bucket: "federation_scalar_1h")
