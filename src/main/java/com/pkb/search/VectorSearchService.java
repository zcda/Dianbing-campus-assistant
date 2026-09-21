package com.pkb.search;

import com.pkb.config.RagProperties;
import com.pkb.llm.VectorCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * pgvector 余弦相似度 Top-K 检索。
 * 相似度 = 1 - 余弦距离(<=>)；低于 min-similarity 的片段在 SQL 层直接过滤，
 * 全部被过滤时上层直接回答"未找到"，不调用 LLM（防编造 + 省钱）。
 */
@Service
public class VectorSearchService {

    private final JdbcClient db;
    private final RagProperties props;

    public VectorSearchService(JdbcClient db, RagProperties props) {
        this.db = db;
        this.props = props;
    }

    public List<Source> search(float[] queryEmbedding) {
        double maxDistance = 1.0 - props.getMinSimilarity();
        return db.sql("""
                        SELECT c.note_id, c.seq, c.content, n.title,
                               1 - (c.embedding <=> CAST(:q AS vector)) AS similarity
                        FROM chunk c
                        JOIN note n ON n.id = c.note_id
                        WHERE c.embedding IS NOT NULL
                          AND (c.embedding <=> CAST(:q AS vector)) <= :maxDist
                        ORDER BY c.embedding <=> CAST(:q AS vector)
                        LIMIT :k
                        """)
                .param("q", VectorCodec.toLiteral(queryEmbedding))
                .param("maxDist", maxDistance)
                .param("k", props.getTopK())
                .query((rs, i) -> new Source(
                        i + 1,
                        rs.getLong("note_id"),
                        rs.getString("title"),
                        rs.getInt("seq"),
                        rs.getString("content"),
                        rs.getDouble("similarity")))
                .list();
    }
}
