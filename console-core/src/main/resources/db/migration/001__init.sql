CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nickname TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('user', 'admin')),
    api_key_hash TEXT NOT NULL UNIQUE,
    api_key_ciphertext TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_api_key_hash ON users(api_key_hash);


CREATE TABLE IF NOT EXISTS credentials (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    reference_id TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    credential_type TEXT NOT NULL CHECK (credential_type IN ('auth_file', 'apikey')),
    enabled INTEGER NOT NULL DEFAULT 1,
    provider TEXT NOT NULL DEFAULT '',
    base_url TEXT NOT NULL DEFAULT '',
    project_id TEXT NOT NULL DEFAULT '',
    success INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    recent_requests TEXT NOT NULL DEFAULT '[]',
    tags TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_credentials_reference_id ON credentials(reference_id);

CREATE TABLE IF NOT EXISTS usage_event_inbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,
    message_hash TEXT NOT NULL,
    raw_event_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processed', 'decode_failed', 'process_failed', 'discarded')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    received_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_event_inbox_status_id ON usage_event_inbox(status, id);
CREATE INDEX IF NOT EXISTS idx_usage_event_inbox_message_hash ON usage_event_inbox(message_hash);
CREATE INDEX IF NOT EXISTS idx_usage_event_inbox_status_updated_at ON usage_event_inbox(status, updated_at);

CREATE TABLE IF NOT EXISTS usage_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_key TEXT NOT NULL DEFAULT '',
    request_id TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    source TEXT NOT NULL DEFAULT '',
    provider TEXT NOT NULL DEFAULT '',
    endpoint TEXT NOT NULL DEFAULT '',
    auth_type TEXT NOT NULL DEFAULT '',
    auth_index TEXT NOT NULL DEFAULT '',
    api_group_key TEXT NOT NULL DEFAULT '',
    api_key_hash TEXT,
    model TEXT NOT NULL DEFAULT 'unknown',
    model_alias TEXT,
    executor_type TEXT NOT NULL DEFAULT '',
    service_tier TEXT NOT NULL DEFAULT '',
    response_service_tier TEXT NOT NULL DEFAULT '',
    reasoning_effort TEXT NOT NULL DEFAULT '',
    failed INTEGER NOT NULL DEFAULT 0,
    generate INTEGER,
    latency_ms INTEGER NOT NULL DEFAULT 0,
    ttft_ms INTEGER,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens INTEGER NOT NULL DEFAULT 0,
    cached_tokens INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    inbox_id INTEGER,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_events_timestamp_id ON usage_events(timestamp DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_usage_events_event_key ON usage_events(event_key);
CREATE INDEX IF NOT EXISTS idx_usage_events_request_id ON usage_events(request_id);
CREATE INDEX IF NOT EXISTS idx_usage_events_api_key_hash ON usage_events(api_key_hash);
CREATE INDEX IF NOT EXISTS idx_usage_events_model ON usage_events(model);
CREATE INDEX IF NOT EXISTS idx_usage_events_provider ON usage_events(provider);
CREATE INDEX IF NOT EXISTS idx_usage_events_source ON usage_events(source);
CREATE INDEX IF NOT EXISTS idx_usage_events_auth_index ON usage_events(auth_index);
