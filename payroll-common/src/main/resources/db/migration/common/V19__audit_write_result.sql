-- H2(MySQL 模式) 不支持单条 ALTER 多列 ADD，拆成独立语句保持双库一致。
ALTER TABLE audit_log ADD COLUMN revision VARCHAR(64) NULL;
ALTER TABLE audit_log ADD COLUMN write_result VARCHAR(16) NULL;
