CREATE TABLE IF NOT EXISTS persistent_login_tokens (
    selector TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_persistent_login_tokens_user_id ON persistent_login_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_persistent_login_tokens_expires_at ON persistent_login_tokens(expires_at);
