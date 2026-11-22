ALTER TABLE audit_log
    ADD COLUMN revision VARCHAR(64) NULL,
    ADD COLUMN write_result VARCHAR(16) NULL;
