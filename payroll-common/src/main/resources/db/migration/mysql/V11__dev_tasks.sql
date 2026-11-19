CREATE TABLE IF NOT EXISTS dev_tasks (
    row_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    role VARCHAR(64) NOT NULL,
    owner_agent VARCHAR(128) NOT NULL DEFAULT '',
    priority INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    depends_on TEXT NULL,
    outputs TEXT NULL,
    acceptance TEXT NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_dev_tasks_plan_task (plan_id, task_id),
    KEY idx_dev_tasks_plan_status (plan_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
