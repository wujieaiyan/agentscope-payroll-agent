package io.agentscope.examples.payroll.kb;

import java.util.List;

/** 知识库模块的 H2 表结构，随 PayrollDatabase 启动时幂等执行。 */
public final class KbSchema {

    private KbSchema() {
    }

    public static final List<String> DDL =
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS kb_series (
                        series_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(50) NOT NULL,
                        description VARCHAR(200) NOT NULL DEFAULT '',
                        dept_id BIGINT NOT NULL,
                        author_id BIGINT NOT NULL,
                        index_strategy VARCHAR(20) NOT NULL DEFAULT 'HYBRID',
                        status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                        active_config_version_id BIGINT,
                        doc_count INT NOT NULL DEFAULT 0,
                        ready_doc_count INT NOT NULL DEFAULT 0,
                        failed_doc_count INT NOT NULL DEFAULT 0,
                        chunk_count BIGINT NOT NULL DEFAULT 0,
                        row_version BIGINT NOT NULL DEFAULT 1,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        deleted_at TIMESTAMP
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_member (
                        member_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        series_id BIGINT NOT NULL,
                        subject_type VARCHAR(10) NOT NULL,
                        subject_id BIGINT NOT NULL,
                        member_role VARCHAR(10) NOT NULL,
                        granted_by BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uk_kb_member UNIQUE (series_id, subject_type, subject_id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_config_version (
                        version_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        series_id BIGINT NOT NULL,
                        version_no INT NOT NULL,
                        index_strategy VARCHAR(20) NOT NULL,
                        parse_config CLOB NOT NULL,
                        chunk_config CLOB NOT NULL,
                        retrieval_config CLOB NOT NULL,
                        change_note VARCHAR(200) NOT NULL DEFAULT '',
                        created_by BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_directory (
                        directory_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        series_id BIGINT NOT NULL,
                        parent_id BIGINT,
                        name VARCHAR(50) NOT NULL,
                        dir_path VARCHAR(512) NOT NULL DEFAULT '/',
                        dir_depth INT NOT NULL DEFAULT 0,
                        is_root BOOLEAN NOT NULL DEFAULT FALSE,
                        sort_order INT NOT NULL DEFAULT 0,
                        created_by BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_document (
                        document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        series_id BIGINT NOT NULL,
                        directory_id BIGINT NOT NULL,
                        name VARCHAR(255) NOT NULL,
                        ext VARCHAR(16) NOT NULL,
                        mime_type VARCHAR(128) NOT NULL,
                        size_bytes BIGINT NOT NULL,
                        sha256 VARCHAR(64) NOT NULL,
                        storage_key VARCHAR(512) NOT NULL,
                        page_count INT,
                        char_count BIGINT,
                        status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                        error_code VARCHAR(64),
                        error_message VARCHAR(500),
                        chunk_count INT NOT NULL DEFAULT 0,
                        config_version_id BIGINT,
                        uploaded_by BIGINT NOT NULL,
                        row_version BIGINT NOT NULL DEFAULT 1,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        deleted_at TIMESTAMP
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_kb_document_series
                        ON kb_document (series_id, deleted_at, updated_at)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_chunk (
                        chunk_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        series_id BIGINT NOT NULL,
                        document_id BIGINT NOT NULL,
                        parent_chunk_id BIGINT,
                        chunk_level VARCHAR(10) NOT NULL,
                        seq INT NOT NULL,
                        content CLOB NOT NULL,
                        content_hash VARCHAR(64) NOT NULL,
                        token_count INT NOT NULL DEFAULT 0,
                        char_start INT,
                        char_end INT,
                        page_no INT,
                        bbox VARCHAR(200),
                        title_path VARCHAR(512),
                        metadata CLOB,
                        vector CLOB,
                        vector_source VARCHAR(16) NOT NULL DEFAULT 'HASH',
                        status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc
                        ON kb_chunk (document_id, seq)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_kb_chunk_series
                        ON kb_chunk (series_id, status)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_tag (
                        tag_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        series_id BIGINT NOT NULL,
                        name VARCHAR(20) NOT NULL,
                        color VARCHAR(16) NOT NULL DEFAULT '#1677ff',
                        created_by BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uk_kb_tag UNIQUE (series_id, name)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_document_tag (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        document_id BIGINT NOT NULL,
                        tag_id BIGINT NOT NULL,
                        series_id BIGINT NOT NULL,
                        CONSTRAINT uk_kb_document_tag UNIQUE (document_id, tag_id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_ingest_task (
                        task_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        series_id BIGINT NOT NULL,
                        document_id BIGINT,
                        task_type VARCHAR(24) NOT NULL,
                        task_status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
                        progress INT NOT NULL DEFAULT 0,
                        stage VARCHAR(32),
                        retry_count INT NOT NULL DEFAULT 0,
                        retry_of_task_id BIGINT,
                        config_version_id BIGINT,
                        idempotency_key VARCHAR(128) NOT NULL,
                        payload CLOB,
                        failed_items CLOB,
                        error_code VARCHAR(64),
                        error_message VARCHAR(500),
                        operator_id BIGINT NOT NULL,
                        started_at TIMESTAMP,
                        finished_at TIMESTAMP,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_kb_task_series
                        ON kb_ingest_task (series_id, task_status, created_at)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_kb_task_doc
                        ON kb_ingest_task (document_id, created_at)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS kb_task_log (
                        log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        stage VARCHAR(32) NOT NULL,
                        log_level VARCHAR(8) NOT NULL DEFAULT 'INFO',
                        message VARCHAR(1000),
                        cost_ms INT,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_kb_task_log
                        ON kb_task_log (task_id, created_at)
                    """);
}
