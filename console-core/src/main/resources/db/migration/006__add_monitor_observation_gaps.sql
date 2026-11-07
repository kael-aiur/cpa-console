-- Monitor observation gap metadata (T07 / T09, D4.3 / D6.2).
-- One half-open millisecond interval per scheduler downtime discovered by startup
-- recovery, plus the durable marker row that survives crashes so the next boot can
-- measure the previous downtime even when no run was active during it.
-- Appends only; never rewrites 004/005. No identity, no secrets, no request bodies.

CREATE TABLE IF NOT EXISTS monitor_observation_gaps (
    gap_id INTEGER PRIMARY KEY AUTOINCREMENT,
    gap_start INTEGER NOT NULL,
    gap_end INTEGER NOT NULL,
    CHECK (gap_end > gap_start),
    CHECK (gap_start >= 0)
);

CREATE INDEX IF NOT EXISTS idx_monitor_observation_gaps_range
    ON monitor_observation_gaps (gap_start, gap_end);

CREATE TABLE IF NOT EXISTS monitor_recovery_marker (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    marker_value INTEGER NOT NULL,
    CHECK (marker_value >= 0)
);
