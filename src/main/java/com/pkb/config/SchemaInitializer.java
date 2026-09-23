package com.pkb.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化表结构。
 * 不用 schema.sql 是因为向量列维度需要跟随配置（rag.embedding-dimension）动态生成。
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private final RagProperties props;

    public SchemaInitializer(JdbcTemplate jdbc, RagProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        int dim = props.getEmbeddingDimension();
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS note (
                    id BIGSERIAL PRIMARY KEY,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    source_type VARCHAR(16) NOT NULL DEFAULT 'manual',
                    source_filename TEXT,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        // 兼容已存在的库：CREATE TABLE IF NOT EXISTS 不会补列，需要显式 ALTER
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS source_type VARCHAR(16) NOT NULL DEFAULT 'manual'");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS source_filename TEXT");
        // 规则元数据：历史个人笔记保持 draft，只有人工核验的 active 规则可检索。
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS category TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS issuer TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS document_no TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS source_url TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS published_at DATE");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS effective_from DATE");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS effective_to DATE");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'draft'");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS audience TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS campus TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS academic_year TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS version TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS original_file BYTEA");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS original_content_type TEXT");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS index_ready BOOLEAN NOT NULL DEFAULT FALSE");
        jdbc.execute("ALTER TABLE note ADD COLUMN IF NOT EXISTS content_revision BIGINT NOT NULL DEFAULT 0");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_note_rule_status ON note(status, effective_from, effective_to)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chunk (
                    id BIGSERIAL PRIMARY KEY,
                    note_id BIGINT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
                    seq INT NOT NULL,
                    content TEXT NOT NULL,
                    content_hash CHAR(64) NOT NULL,
                    embedding vector(%d)
                )""".formatted(dim));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS embedding_cache (
                    content_hash CHAR(64) PRIMARY KEY,
                    embedding vector(%d) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""".formatted(dim));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversation (
                    id BIGSERIAL PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
                    role VARCHAR(16) NOT NULL,
                    content TEXT NOT NULL,
                    sources JSONB,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_note_id ON chunk(note_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_id ON chat_message(conversation_id)");
        // 余弦距离 HNSW 索引
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops)");

        // ===== 改造 v1 新增（设计文档 §6，全部 IF NOT EXISTS，老库直接启动升级） =====

        // ① 关键词通道（D2）：应用层写入 bigram 切分后的 tsvector（不用 generated column，
        //    换分词器只需改一个类 + 重建索引，不用改 DDL）；历史切片 tsv 为 NULL 不参与关键词通道
        jdbc.execute("ALTER TABLE chunk ADD COLUMN IF NOT EXISTS tsv tsvector");
        jdbc.execute("ALTER TABLE chunk ADD COLUMN IF NOT EXISTS token_count INT");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_tsv_gin ON chunk USING GIN (tsv)");

        // ② 父块-子块多粒度召回（D10，仅预留列，逻辑非必做）
        jdbc.execute("ALTER TABLE chunk ADD COLUMN IF NOT EXISTS parent_id BIGINT REFERENCES chunk(id) ON DELETE CASCADE");
        jdbc.execute("ALTER TABLE chunk ADD COLUMN IF NOT EXISTS level VARCHAR(8) NOT NULL DEFAULT 'child'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_parent_id ON chunk(parent_id)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_note_level_seq ON chunk(note_id, level, seq)");

        // ③ 异步索引任务（D9）：幂等靠部分唯一索引，不靠代码判断 —— 同一笔记不会堆积多个未完成任务
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS index_task (
                    id          BIGSERIAL PRIMARY KEY,
                    note_id     BIGINT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
                    type        VARCHAR(16) NOT NULL DEFAULT 'upsert',
                    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    attempts    INT NOT NULL DEFAULT 0,
                    last_error  TEXT,
                    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_index_task_active
                ON index_task(note_id) WHERE status IN ('PENDING','RUNNING')""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_index_task_status ON index_task(status, created_at)");

        // ④ 会话消息状态（D6：被中断/被限流的半截消息不占改写历史名额）
        jdbc.execute("ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'NORMAL'");

        // ⑤ 词表映射（FR-15：同义词/别名归一）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS query_term_mapping (
                    id          BIGSERIAL PRIMARY KEY,
                    source_term TEXT NOT NULL UNIQUE,
                    target_term TEXT NOT NULL,
                    priority    INT NOT NULL DEFAULT 0,
                    enabled     BOOLEAN NOT NULL DEFAULT TRUE
                )""");

        // ⑥ 评测录制（D7：录制/评分分离，run 之间可比，含配置快照）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS eval_run (
                    id         BIGSERIAL PRIMARY KEY,
                    tag        TEXT NOT NULL,
                    config     JSONB NOT NULL,
                    metrics    JSONB,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS eval_record (
                    id            BIGSERIAL PRIMARY KEY,
                    run_id        BIGINT NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
                    query_id      TEXT NOT NULL,
                    question      TEXT NOT NULL,
                    sub_questions JSONB,
                    retrieved     JSONB NOT NULL,
                    answer        TEXT,
                    done_reason   TEXT,
                    timings       JSONB,
                    judge         JSONB,
                    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_eval_record_run ON eval_record(run_id, query_id)");

        Long missingTsv = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk WHERE tsv IS NULL", Long.class);
        if (missingTsv != null && missingTsv > 0) {
            log.warn("检测到 {} 条历史切片 tsv 为空（不参与关键词通道），请执行 POST /api/index/rebuild 全量重建回填",
                    missingTsv);
        }
        log.info("PKB 数据库表结构初始化完成（embedding 维度={}）", dim);
    }
}
