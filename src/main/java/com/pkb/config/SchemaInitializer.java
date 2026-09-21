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
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
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
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_note_id ON chunk(note_id)");
        // 余弦距离 HNSW 索引
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops)");
        log.info("PKB 数据库表结构初始化完成（embedding 维度={}）", dim);
    }
}
