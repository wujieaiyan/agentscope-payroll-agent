CREATE TABLE IF NOT EXISTS chat_attachments (
    attachment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    message_id BIGINT NULL,
    name VARCHAR(255) NOT NULL,
    ext VARCHAR(16) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    text_chars INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    error VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_chat_attachments_message ON chat_attachments (session_id, message_id);
CREATE INDEX idx_chat_attachments_user ON chat_attachments (user_id, created_at);
