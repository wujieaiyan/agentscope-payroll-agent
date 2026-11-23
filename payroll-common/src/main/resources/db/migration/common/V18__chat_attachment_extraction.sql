-- OCR 能力演进 S1：附件解析改为真实异步态，并记录提取溯源。
-- progress：PARSING 期间 0-100；extraction_meta：引擎/模型/语言/置信度/耗时/源图 SHA-256 与质量门结果。
-- H2 由 PayrollDatabase 内联 DDL 同步创建同构结构。
ALTER TABLE chat_attachments ADD COLUMN progress INT NOT NULL DEFAULT 0;
ALTER TABLE chat_attachments ADD COLUMN extraction_meta VARCHAR(4096) NULL;
