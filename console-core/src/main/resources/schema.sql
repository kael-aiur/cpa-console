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
