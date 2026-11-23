-- P1-3：每会话围栏计数器。开轮时以行锁自增，Redis 降级与多实例并发下也不会分配重复令牌。
CREATE TABLE IF NOT EXISTS chat_fencing (
    session_id BIGINT NOT NULL PRIMARY KEY,
    fencing_token BIGINT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
