package com.pkb.search.channel;

import com.pkb.config.RagProperties;
import com.pkb.search.RetrievedChunk;
import com.pkb.search.RuleReference;
import com.pkb.search.tokenizer.BigramTokenizer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 关键词通道（D2）：tsvector(bigram) + GIN，ts_rank 排序，补上"术语精确匹配"能力。
 * 语义向量对专有名词不敏感（ConcurrentHashMap、Minor GC 这类查询），这正是关键词检索的强项。
 * tsv 由写入侧（NoteService）用 BigramTokenizer 应用层生成；历史切片 tsv 为 NULL 不参与本通道。
 *
 * <p>查询语义取舍（实测修正文档方案 b）：plainto_tsquery 的 AND 语义要求问句所有 bigram 全部命中，
 * 自然语言问句里的"什么/怎么"等常见 bigram 使 AND 几乎恒无命中 —— 通道形同虚设。
 * 改为 OR 语义（to_tsquery + 引号字面量防解析歧义）+ ts_rank 密度排序：
 * 命中词多、词频密的块排前；防噪声不靠 AND，靠 RRF 降权（0.6）+ 批级闸门
 * （keyword-only 命中的归一化 RRF 分通常低于闸门阈值，单薄命中过不了闸）。
 */
@Component
public class KeywordSearchChannel implements SearchChannel {

    private final JdbcClient db;
    private final RagProperties props;
    private final BigramTokenizer tokenizer;

    public KeywordSearchChannel(JdbcClient db, RagProperties props, BigramTokenizer tokenizer) {
        this.db = db;
        this.props = props;
        this.tokenizer = tokenizer;
    }

    @Override
    public SearchChannelType type() {
        return SearchChannelType.KEYWORD;
    }

    @Override
    public boolean isEnabled() {
        return props.getChannels().getKeyword().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext ctx) {
        List<String> tokens = tokenizer.tokens(ctx.query());
        if (tokens.isEmpty()) {
            return new SearchChannelResult(type(), List.of());
        }
        // OR 语义 + 引号字面量（防 to_tsquery 把 token 中的 - . 等当操作符）
        String expr = tokens.stream()
                .map(t -> "\"" + t.replace("\"", "") + "\"")
                .collect(Collectors.joining(" | "));
        List<RetrievedChunk> hits = db.sql("""
                        SELECT c.note_id, c.seq, c.content, n.title, n.category, n.issuer,
                               n.document_no, n.source_url, n.published_at, n.effective_from,
                               n.effective_to, n.audience, n.campus, n.academic_year, n.version,
                               ts_rank(c.tsv, q.terms) AS rank
                        FROM chunk c
                        JOIN note n ON n.id = c.note_id
                        CROSS JOIN to_tsquery('simple', :expr) AS q(terms)
                        WHERE c.tsv IS NOT NULL
                          AND n.status = 'active'
                          AND n.index_ready = TRUE
                          AND (n.effective_from IS NULL OR n.effective_from <= CURRENT_DATE)
                          AND (n.effective_to IS NULL OR n.effective_to >= CURRENT_DATE)
                          AND c.tsv @@ q.terms
                        ORDER BY ts_rank(c.tsv, q.terms) DESC, c.id
                        LIMIT :limit
                        """)
                .param("expr", expr)
                .param("limit", props.getRetrieval().getCandidateLimit())
                .query((rs, i) -> new RetrievedChunk(
                        rs.getLong("note_id"),
                        rs.getInt("seq"),
                        rs.getString("title"),
                        rs.getString("content"))
                        .rule(RuleReference.from(rs))
                        .keywordScore(rs.getDouble("rank")))
                .list();
        hits.forEach(h -> h.hitChannels().add(SearchChannelType.KEYWORD));
        return new SearchChannelResult(type(), hits);
    }
}
