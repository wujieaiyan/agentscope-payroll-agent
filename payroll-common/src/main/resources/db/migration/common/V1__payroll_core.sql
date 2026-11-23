-- MySQL 8.0 基线：薪酬管理台全部业务表。
-- H2 仍由 PayrollDatabase 内联 DDL 建表；本脚本是 MySQL 的唯一建表来源，由 Flyway 版本化管理。
-- 注意：H2 的 CLOB 统一映射为 LONGTEXT；ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS
-- 在 MySQL 中不支持，已折叠进建表语句。

CREATE TABLE departments (
    department_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(200) NOT NULL DEFAULT '',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE roles (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(200) NOT NULL DEFAULT '',
    permissions_json LONGTEXT NOT NULL,
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE app_users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    department_id BIGINT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE chat_sessions (
    session_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    context_summary LONGTEXT,
    context_turns INT NOT NULL DEFAULT 0,
    persona_key VARCHAR(128)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_chat_sessions_user ON chat_sessions (user_id, updated_at);

CREATE TABLE chat_messages (
    message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    tools_json LONGTEXT,
    model VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_chat_messages_session ON chat_messages (session_id, message_id);

CREATE TABLE chat_tool_traces (
    trace_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    tool_call_id VARCHAR(128),
    tool_name VARCHAR(64) NOT NULL,
    agent VARCHAR(32),
    args_json LONGTEXT,
    result_json LONGTEXT,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    result_state VARCHAR(16),
    duration_ms BIGINT NOT NULL DEFAULT 0,
    employees_json LONGTEXT,
    period VARCHAR(16),
    department VARCHAR(64),
    amounts_json LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    run_id BIGINT,
    node_id VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_chat_tool_traces_message ON chat_tool_traces (message_id, trace_id);
CREATE INDEX idx_chat_tool_traces_session ON chat_tool_traces (session_id);
CREATE INDEX idx_chat_tool_traces_run ON chat_tool_traces (run_id, node_id);

CREATE TABLE employees (
    employee_id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    department VARCHAR(64) NOT NULL,
    job_title VARCHAR(64),
    base_salary DECIMAL(14, 2) NOT NULL,
    position_allowance DECIMAL(14, 2) NOT NULL DEFAULT 0,
    housing_fund_rate DECIMAL(8, 4) NOT NULL DEFAULT 0,
    social_insurance_base DECIMAL(14, 2),
    special_additional_deduction DECIMAL(14, 2) NOT NULL DEFAULT 0,
    hire_date DATE NOT NULL,
    bank_account_masked VARCHAR(64),
    email VARCHAR(128),
    city VARCHAR(32) NOT NULL DEFAULT '默认',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE attendance (
    employee_id VARCHAR(32) NOT NULL,
    period VARCHAR(7) NOT NULL,
    personal_leave_days DECIMAL(6, 2) NOT NULL DEFAULT 0,
    sick_leave_days DECIMAL(6, 2) NOT NULL DEFAULT 0,
    absent_days DECIMAL(6, 2) NOT NULL DEFAULT 0,
    weekday_overtime_hours DECIMAL(8, 2) NOT NULL DEFAULT 0,
    rest_day_overtime_hours DECIMAL(8, 2) NOT NULL DEFAULT 0,
    holiday_overtime_hours DECIMAL(8, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (employee_id, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE variable_pay (
    employee_id VARCHAR(32) NOT NULL,
    period VARCHAR(7) NOT NULL,
    performance_bonus DECIMAL(14, 2) NOT NULL DEFAULT 0,
    other_allowance DECIMAL(14, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (employee_id, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE performance_reviews (
    employee_id VARCHAR(32) NOT NULL,
    period VARCHAR(7) NOT NULL,
    score DECIMAL(6, 2) NOT NULL,
    coefficient DECIMAL(8, 4) NOT NULL,
    review_comment VARCHAR(500),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (employee_id, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE city_policies (
    city VARCHAR(32) PRIMARY KEY,
    policy_json LONGTEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payroll_runs (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period VARCHAR(7) NOT NULL,
    scope VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    employee_count INT NOT NULL,
    total_gross DECIMAL(16, 2) NOT NULL,
    total_deductions DECIMAL(16, 2) NOT NULL,
    total_net DECIMAL(16, 2) NOT NULL,
    total_employer_cost DECIMAL(16, 2) NOT NULL,
    excel_path VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at TIMESTAMP NULL,
    workflow_steps_json LONGTEXT,
    current_step INT NOT NULL DEFAULT 0,
    approval_flow_id BIGINT,
    approval_flow_name VARCHAR(64),
    submitted_by_id BIGINT,
    submitted_by_name VARCHAR(64),
    submitted_at TIMESTAMP NULL,
    submit_comment VARCHAR(500),
    approved_by_id BIGINT,
    approved_by_name VARCHAR(64),
    approved_at TIMESTAMP NULL,
    approval_comment VARCHAR(500),
    rejected_by_id BIGINT,
    rejected_by_name VARCHAR(64),
    rejected_at TIMESTAMP NULL,
    rejection_reason VARCHAR(500),
    snapshot_hash VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payroll_details (
    run_id BIGINT NOT NULL,
    employee_id VARCHAR(32) NOT NULL,
    employee_name VARCHAR(64),
    department VARCHAR(64),
    city VARCHAR(32),
    email VARCHAR(128),
    adjustment_json LONGTEXT,
    result_json LONGTEXT NOT NULL,
    email_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    email_reason VARCHAR(500),
    PRIMARY KEY (run_id, employee_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payroll_run_failures (
    run_id BIGINT NOT NULL,
    employee_id VARCHAR(32) NOT NULL,
    message VARCHAR(500),
    PRIMARY KEY (run_id, employee_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE email_deliveries (
    delivery_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    employee_id VARCHAR(32) NOT NULL,
    email VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE approval_flows (
    flow_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_value VARCHAR(64) NOT NULL,
    min_amount DECIMAL(16, 2) NOT NULL DEFAULT 0,
    max_amount DECIMAL(16, 2),
    priority INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(200) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE approval_flow_steps (
    step_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flow_id BIGINT NOT NULL,
    step_order INT NOT NULL,
    name VARCHAR(64) NOT NULL,
    approver_type VARCHAR(16) NOT NULL,
    approver_value VARCHAR(64) NOT NULL,
    min_amount DECIMAL(16, 2),
    max_amount DECIMAL(16, 2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_approval_flow_steps_flow ON approval_flow_steps (flow_id, step_order);
CREATE INDEX idx_approval_flows_scope ON approval_flows (scope_type, scope_value, enabled);

CREATE TABLE payroll_approval_events (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_id BIGINT,
    actor_name VARCHAR(64),
    flow_id BIGINT,
    flow_name VARCHAR(64),
    comment VARCHAR(500),
    snapshot_hash VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    step_order INT,
    step_name VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_approval_events_run ON payroll_approval_events (run_id, event_id);

CREATE TABLE audit_log (
    audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id BIGINT,
    actor_name VARCHAR(64),
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32),
    resource_id VARCHAR(256),
    before_json LONGTEXT,
    after_json LONGTEXT,
    payload_json LONGTEXT,
    ip VARCHAR(64),
    user_agent VARCHAR(256),
    result VARCHAR(16) NOT NULL,
    status_code INT,
    message VARCHAR(500),
    trace_id VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_audit_occurred ON audit_log (occurred_at);
CREATE INDEX idx_audit_actor ON audit_log (actor_id, occurred_at);
CREATE INDEX idx_audit_resource ON audit_log (resource_type, resource_id, occurred_at);

CREATE TABLE agent_team_runs (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    run_mode VARCHAR(16) NOT NULL,
    task_type VARCHAR(32),
    template_id VARCHAR(64),
    plan_json LONGTEXT NOT NULL,
    catalog_snapshot_json LONGTEXT,
    score_snapshot_json LONGTEXT,
    status VARCHAR(24) NOT NULL,
    fallback_reason VARCHAR(256),
    baseline_run_id BIGINT,
    fallback_of_run_id BIGINT,
    retry_of_run_id BIGINT,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    total_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP NULL,
    archived_at TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_agent_team_runs_message ON agent_team_runs (message_id);
CREATE INDEX idx_agent_team_runs_session ON agent_team_runs (session_id);
CREATE INDEX idx_agent_team_runs_primary ON agent_team_runs (message_id, is_primary);

CREATE TABLE agent_run_records (
    record_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    stage_key VARCHAR(64),
    agent_id VARCHAR(64),
    agent_role VARCHAR(32),
    status VARCHAR(24) NOT NULL,
    tool_calls INT NOT NULL DEFAULT 0,
    tool_errors INT NOT NULL DEFAULT 0,
    validation_passed BOOLEAN,
    output_chars INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    trace_ids_json LONGTEXT,
    input_digest VARCHAR(64),
    message_json LONGTEXT,
    error_message VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_agent_run_records_run ON agent_run_records (run_id, node_id);
CREATE INDEX idx_agent_run_records_agent ON agent_run_records (agent_id, created_at);

CREATE TABLE chat_feedback (
    feedback_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating VARCHAR(8) NOT NULL,
    tags_json LONGTEXT,
    comment_text VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_feedback_message_user UNIQUE (message_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_chat_feedback_message ON chat_feedback (message_id);
