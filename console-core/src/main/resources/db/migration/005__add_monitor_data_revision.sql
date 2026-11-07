-- Monitor data revision counter (T05).
-- Single-row monotonic counter incremented by every recognised writing transaction
-- (claim, terminal state, task edit, retention cleanup) so paginated readers can detect
-- cross-request snapshot changes (D5.1 / D6.3). Appends only; never rewrites 004.

CREATE TABLE IF NOT EXISTS monitor_data_revision (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision INTEGER NOT NULL DEFAULT 0,
    CHECK (revision >= 0)
);
