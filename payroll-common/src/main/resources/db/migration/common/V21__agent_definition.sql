-- 智能体定义落库（架构加固 M5.1）：数据库为权威，Nacos 退化为变更通知通道。
-- payload_json 存单个 AgentDefinition 的 JSON；revision 行级递增，写前重读比对（409）。
CREATE TABLE agent_definition (
    id VARCHAR(128) PRIMARY KEY,
    payload_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL,
    updated_by VARCHAR(128),
    updated_at TIMESTAMP NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
