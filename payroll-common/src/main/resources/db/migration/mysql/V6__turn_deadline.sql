-- P1-2：轮次绝对截止时间。创建时固定，续流不再按每次连接重置；
-- 执行节点整体失联时由周期扫描按 deadline_at 收尾。
ALTER TABLE chat_turns ADD COLUMN deadline_at TIMESTAMP NULL;
UPDATE chat_turns
SET deadline_at = DATE_ADD(started_at, INTERVAL 30 MINUTE)
WHERE deadline_at IS NULL AND finished_at IS NULL;
CREATE INDEX idx_chat_turns_deadline ON chat_turns (status, deadline_at);
