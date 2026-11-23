CREATE TABLE IF NOT EXISTS dev_deliveries (
    delivery_id VARCHAR(64) PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    branch VARCHAR(120) NOT NULL,
    base_commit VARCHAR(64) NOT NULL DEFAULT '',
    pr_title VARCHAR(200) NOT NULL DEFAULT '',
    pr_body MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    patches_json MEDIUMTEXT NOT NULL,
    commit_id VARCHAR(64) NOT NULL DEFAULT '',
    decided_by VARCHAR(64) NOT NULL DEFAULT '',
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP NULL,
    KEY idx_dev_deliveries_plan (plan_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
