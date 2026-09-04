CREATE TABLE IF NOT EXISTS available_models (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    owned_by TEXT NOT NULL DEFAULT '',
    tags TEXT NOT NULL DEFAULT '[]',
    litellm_model_id TEXT,
    discovered_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_available_models_name ON available_models(name);
CREATE INDEX IF NOT EXISTS idx_available_models_litellm_model_id ON available_models(litellm_model_id);

CREATE TABLE IF NOT EXISTS litellm_model_metadata (
    model_id TEXT PRIMARY KEY,
    provider TEXT NOT NULL DEFAULT '',
    mode TEXT NOT NULL DEFAULT '',
    max_input_tokens INTEGER,
    max_output_tokens INTEGER,
    max_tokens INTEGER,
    metadata_json TEXT NOT NULL,
    synced_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS litellm_sync_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    url TEXT NOT NULL,
    proxy_enabled INTEGER NOT NULL DEFAULT 0,
    proxy_host TEXT NOT NULL DEFAULT '127.0.0.1',
    proxy_port INTEGER NOT NULL DEFAULT 7890,
    updated_at TIMESTAMP NOT NULL
);

INSERT INTO litellm_sync_config (id, url, proxy_enabled, proxy_host, proxy_port, updated_at)
VALUES (1, 'https://raw.githubusercontent.com/BerriAI/litellm/refs/heads/litellm_internal_staging/model_prices_and_context_window.json', 0, '127.0.0.1', 7890, CURRENT_TIMESTAMP)
ON CONFLICT(id) DO NOTHING;
