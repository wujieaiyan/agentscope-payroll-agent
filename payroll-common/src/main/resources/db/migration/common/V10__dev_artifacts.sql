CREATE TABLE IF NOT EXISTS dev_artifacts (
    artifact_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    owner VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    content LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    diff_hash VARCHAR(128) NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_dev_artifacts_version (task_id, artifact_type, version),
    KEY idx_dev_artifacts_task (task_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
