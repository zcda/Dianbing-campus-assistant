package com.dianbing.qa.rewrite;

import com.dianbing.infrastructure.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 词表映射（FR-15）：query_term_mapping 表驱动的同义词归一，纯规则零 token。
 * 优先级规则：priority 大的先替换；同 priority 时长词优先（避免短词误伤长词）。
 * ASCII 词做非字母数字边界约束，防止 "gc" 替换掉 "ConcurrentHashMap" 内部片段。
 */
@Component
public class JdbcTermMapper implements TermMapper {

    private static final Logger log = LoggerFactory.getLogger(JdbcTermMapper.class);

    private final JdbcClient db;
    private final RagProperties props;

    private volatile List<Mapping> mappings = List.of();

    public JdbcTermMapper(JdbcClient db, RagProperties props) {
        this.db = db;
        this.props = props;
    }

    @PostConstruct
    void load() {
        if (!props.getTermMapping().isEnabled()) {
            log.info("词表映射未启用（rag.term-mapping.enabled=false）");
            return;
        }
        reload();
    }

    /** 重新加载词表（改表后重启或调用此方法生效） */
    public synchronized void reload() {
        List<Mapping> loaded = db.sql("""
                        SELECT source_term, target_term, priority
                        FROM query_term_mapping WHERE enabled = TRUE
                        """)
                .query((rs, i) -> new Mapping(rs.getString("source_term"), rs.getString("target_term"),
                        rs.getInt("priority")))
                .list();
        // priority 降序、同 priority 长词优先
        loaded.sort(Comparator.comparingInt(Mapping::priority).reversed()
                .thenComparing(m -> -m.source().length()));
        this.mappings = List.copyOf(loaded);
        log.info("词表映射已加载 {} 条", loaded.size());
    }

    @Override
    public String normalize(String text) {
        if (!props.getTermMapping().isEnabled() || text == null || text.isBlank() || mappings.isEmpty()) {
            return text;
        }
        String result = text;
        for (Mapping mapping : mappings) {
            Pattern pattern = Pattern.compile(
                    "(?<![A-Za-z0-9])" + Pattern.quote(mapping.source()) + "(?![A-Za-z0-9])",
                    Pattern.CASE_INSENSITIVE);
            result = pattern.matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(mapping.target()));
        }
        return result;
    }

    private record Mapping(String source, String target, int priority) {
    }
}
