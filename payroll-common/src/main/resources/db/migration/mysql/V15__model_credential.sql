CREATE TABLE IF NOT EXISTS model_credential (
    credential_id VARCHAR(64) PRIMARY KEY,
    connection_id VARCHAR(64) NOT NULL,
    cipher_text VARCHAR(2048) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    key_version INT NOT NULL DEFAULT 1,
    created_by VARCHAR(64) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NULL,
    UNIQUE KEY idx_model_credential_connection (connection_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
