-- Credential monitoring schema (T01).
-- New tables are fully independent of credentials/usage; no FK cascades and no secret material.

CREATE TABLE IF NOT EXISTS monitor_cpa_binding (
    instance_key TEXT PRIMARY KEY,
    normalized_endpoint_cipher TEXT NOT NULL,
    endpoint_binding_digest TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    CHECK (length(instance_key) > 0),
    CHECK (length(normalized_endpoint_cipher) > 0),
    CHECK (length(endpoint_binding_digest) > 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_monitor_binding_singleton
    ON monitor_cpa_binding ((1));

CREATE TABLE IF NOT EXISTS monitor_tasks (
    task_id TEXT PRIMARY KEY,
    safe_name TEXT NOT NULL,
    route_model_cipher TEXT NOT NULL,
    safe_model_display TEXT NOT NULL,
    cpa_instance_key TEXT NOT NULL,
    auth_id_cipher TEXT NOT NULL,
    auth_index_cipher TEXT NOT NULL,
    provider_snapshot TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    blocked_reason TEXT,
    schedule_revision INTEGER NOT NULL DEFAULT 1,
    cron_expression TEXT NOT NULL,
    zone_id TEXT NOT NULL,
    tzdb_certificate TEXT NOT NULL,
    next_fire_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    row_version INTEGER NOT NULL DEFAULT 0,
    CHECK (length(task_id) > 0),
    CHECK (length(safe_name) > 0 AND length(safe_name) <= 128),
    CHECK (length(route_model_cipher) > 0),
    CHECK (length(safe_model_display) > 0),
    CHECK (length(cpa_instance_key) > 0),
    CHECK (length(auth_id_cipher) > 0),
    CHECK (length(auth_index_cipher) > 0),
    CHECK (length(provider_snapshot) > 0),
    CHECK (length(cron_expression) > 0),
    CHECK (length(zone_id) > 0),
    CHECK (length(tzdb_certificate) > 0),
    CHECK (schedule_revision >= 1),
    CHECK (row_version >= 0)
);

CREATE TABLE IF NOT EXISTS monitor_runs (
    run_seq INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL UNIQUE,
    task_id TEXT NOT NULL,
    scheduled_at INTEGER NOT NULL,
    schedule_revision INTEGER NOT NULL,
    task_safe_name TEXT NOT NULL,
    task_model_display TEXT NOT NULL,
    cpa_instance_key TEXT NOT NULL,
    auth_id_cipher TEXT NOT NULL,
    auth_index_cipher TEXT NOT NULL,
    provider_snapshot TEXT NOT NULL,
    phase TEXT NOT NULL CHECK (phase IN ('CLAIMED', 'DISPATCHING', 'TERMINAL')),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'SKIPPED', 'SUCCESS', 'FAILED', 'UNKNOWN', 'CONFIG_ERROR')),
    dispatch_status TEXT NOT NULL CHECK (dispatch_status IN ('NOT_SENT', 'MAY_HAVE_SENT')),
    owns_task_slot INTEGER NOT NULL DEFAULT 0 CHECK (owns_task_slot IN (0, 1)),
    claimed_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER,
    duration_ms INTEGER,
    reason_code TEXT,
    safe_admin_summary TEXT CHECK (safe_admin_summary IS NULL OR length(safe_admin_summary) <= 512),
    cpa_http_status INTEGER,
    current_verified INTEGER CHECK (current_verified IS NULL OR current_verified IN (0, 1)),
    CHECK (phase != 'TERMINAL' OR finished_at IS NOT NULL),
    CHECK (phase = 'TERMINAL' OR finished_at IS NULL),
    CHECK (state != 'PENDING' OR phase = 'CLAIMED'),
    CHECK (state != 'SKIPPED' OR dispatch_status = 'NOT_SENT'),
    CHECK (state != 'SKIPPED' OR started_at IS NULL),
    CHECK (state != 'SKIPPED' OR phase = 'TERMINAL'),
    CHECK (state NOT IN ('SUCCESS', 'FAILED') OR (
        started_at IS NOT NULL AND dispatch_status = 'MAY_HAVE_SENT' AND phase = 'TERMINAL' AND finished_at IS NOT NULL)),
    CHECK (state != 'UNKNOWN' OR (
        (started_at IS NULL AND dispatch_status = 'NOT_SENT') OR (started_at IS NOT NULL AND dispatch_status = 'MAY_HAVE_SENT'))),
    CHECK (state != 'CONFIG_ERROR' OR (
        (started_at IS NULL AND dispatch_status = 'NOT_SENT') OR (started_at IS NOT NULL AND dispatch_status = 'MAY_HAVE_SENT'))),
    CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CHECK (reason_code IS NULL OR length(reason_code) > 0),
    UNIQUE (task_id, scheduled_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_monitor_runs_task_slot
    ON monitor_runs (task_id) WHERE owns_task_slot = 1;

CREATE TABLE IF NOT EXISTS monitor_credential_guards (
    credential_key TEXT PRIMARY KEY,
    active_run_id TEXT,
    cooldown_until INTEGER,
    updated_at INTEGER NOT NULL,
    CHECK (length(credential_key) > 0)
);

CREATE TABLE IF NOT EXISTS monitor_retention_meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    collection_started_at INTEGER NOT NULL,
    retention_revision INTEGER NOT NULL DEFAULT 0,
    last_cleanup_at INTEGER,
    CHECK (collection_started_at >= 0),
    CHECK (retention_revision >= 0)
);

CREATE TABLE IF NOT EXISTS monitor_retention_losses (
    loss_id INTEGER PRIMARY KEY AUTOINCREMENT,
    interval_start INTEGER NOT NULL,
    interval_end INTEGER NOT NULL,
    precision TEXT NOT NULL DEFAULT 'exact' CHECK (precision IN ('exact', 'conservative')),
    created_at INTEGER NOT NULL,
    retention_revision INTEGER NOT NULL DEFAULT 0,
    CHECK (interval_start >= 0),
    CHECK (interval_end > interval_start),
    CHECK (retention_revision >= 0)
);

CREATE INDEX IF NOT EXISTS idx_monitor_tasks_enabled_blocked_next
    ON monitor_tasks (enabled, blocked_reason, next_fire_at);
CREATE INDEX IF NOT EXISTS idx_monitor_runs_task_started
    ON monitor_runs (task_id, started_at, run_seq);
CREATE INDEX IF NOT EXISTS idx_monitor_runs_task_scheduled
    ON monitor_runs (task_id, scheduled_at, run_seq);
CREATE INDEX IF NOT EXISTS idx_monitor_runs_finished
    ON monitor_runs (finished_at, run_seq);
CREATE INDEX IF NOT EXISTS idx_monitor_runs_state
    ON monitor_runs (state, run_seq);
CREATE INDEX IF NOT EXISTS idx_monitor_losses_range
    ON monitor_retention_losses (interval_start, interval_end);
