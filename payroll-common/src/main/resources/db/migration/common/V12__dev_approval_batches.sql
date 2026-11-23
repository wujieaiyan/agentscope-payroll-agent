CREATE TABLE IF NOT EXISTS dev_approval_batches (
    batch_id VARCHAR(64) PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    summary VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL,
    items_json MEDIUMTEXT NOT NULL,
    decided_by VARCHAR(64) NOT NULL DEFAULT '',
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP NULL,
    KEY idx_dev_approval_plan (plan_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
