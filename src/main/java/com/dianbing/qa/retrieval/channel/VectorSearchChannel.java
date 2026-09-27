package com.dianbing.qa.retrieval.channel;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.infrastructure.llm.VectorCodec;
import com.dianbing.qa.retrieval.RetrievedChunk;
import com.dianbing.qa.retrieval.RuleReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量通道（D4 表格"单条过滤"层）：pgvector 余弦相似度 Top-candidateLimit。
 * 相似度阈值（rag.min-similarity）在 SQL 层过滤，作用是省 IO 与计算、拦掉明显不相关的单条；
 * "要不要调 LLM"由批级闸门（EvidenceGatePostProcessor）决定，两层护栏分工明确。
 */
@Component
public class VectorSearchChannel implements SearchChannel {

    private final JdbcClient db;
    private final RagProperties props;

    public VectorSearchChannel(JdbcClient db, RagProperties props) {
        this.db = db;
        this.props = props;
    }

    @Override
    public SearchChannelType type() {
        return SearchChannelType.VECTOR;
    }

    @Override
    public boolean isEnabled() {
        return props.getChannels().getVector().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext ctx) {
        if (ctx.queryVector() == null || ctx.queryVector().length == 0) {
            return new SearchChannelResult(type(), List.of());
        }
        double maxDistance = 1.0 - props.getMinSimilarity();
        List<RetrievedChunk> hits = db.sql("""
                        SELECT c.note_id, c.seq, c.content, n.title, n.category, n.issuer,
                               n.document_no, n.source_url, n.published_at, n.effective_from,
                               n.effective_to, n.audience, n.campus, n.academic_year, n.version,
                               1 - (c.embedding <=> CAST(:q AS vector)) AS similarity
                        FROM chunk c
                        JOIN note n ON n.id = c.note_id
                        WHERE c.embedding IS NOT NULL
                          AND n.status = 'active'
                          AND n.index_ready = TRUE
                          AND (n.effective_from IS NULL OR n.effective_from <= CURRENT_DATE)
                          AND (n.effective_to IS NULL OR n.effective_to >= CURRENT_DATE)
                          AND (CAST(:audience AS text) = '' OR n.audience ILIKE CAST(:audience AS text))
                          AND (CAST(:campus AS text) = '' OR n.campus ILIKE CAST(:campus AS text))
                          AND (CAST(:academicYear AS text) = '' OR
                               left(regexp_replace(n.academic_year, '[^0-9]', '', 'g'), 4) = CAST(:academicYear AS text))
                          AND (c.embedding <=> CAST(:q AS vector)) <= :maxDist
                        ORDER BY c.embedding <=> CAST(:q AS vector)
                        LIMIT :limit
                        """)
                .param("q", VectorCodec.toLiteral(ctx.queryVector()))
                .param("audience", ctx.ruleScope().audiencePattern())
                .param("campus", ctx.ruleScope().campusPattern())
                .param("academicYear", ctx.ruleScope().academicYearValue())
                .param("maxDist", maxDistance)
                .param("limit", props.getRetrieval().getCandidateLimit())
                .query((rs, i) -> new RetrievedChunk(
                        rs.getLong("note_id"),
                        rs.getInt("seq"),
                        rs.getString("title"),
                        rs.getString("content"))
                        .rule(RuleReference.from(rs))
                        .vectorScore(rs.getDouble("similarity")))
                .list();
        hits.forEach(h -> h.hitChannels().add(SearchChannelType.VECTOR));
        return new SearchChannelResult(type(), hits);
    }
}
