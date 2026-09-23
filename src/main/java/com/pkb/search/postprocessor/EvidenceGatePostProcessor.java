package com.pkb.search.postprocessor;

import com.pkb.config.RagProperties;
import com.pkb.search.RetrievedChunk;
import com.pkb.search.channel.SearchChannelResult;
import com.pkb.search.channel.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 批级证据闸门（order=15，设计文档 D4）：把"库里没答案就不答"从模型自觉变成系统保证。
 *
 * <p>批级判定而非逐条判定：取本批 max(gateScore) 与阈值比较，不合格整批丢弃。
 * 理由：误丢比误放贵；逐条判会把"过线证据的弱兄弟"一起砍掉，反而丢上下文。
 *
 * <p>降级契约：无分可读一律放行 + WARN（fail-open）—— 降级路径绝不能制造假阴性，
 * 否则精排出问题时表现会和"库里真没资料"完全一样，无法事后区分。
 */
@Component
public class EvidenceGatePostProcessor implements SearchResultPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(EvidenceGatePostProcessor.class);

    private final RagProperties props;

    public EvidenceGatePostProcessor(RagProperties props) {
        this.props = props;
    }

    @Override
    public String getName() { return "EvidenceGate"; }

    @Override
    public int getOrder() { return 15; }

    @Override
    public boolean isEnabled(SearchContext ctx) {
        return "batch".equalsIgnoreCase(props.getEvidence().getMode())
                && props.getEvidence().getMinGateScore() > 0;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> candidates,
                                        List<SearchChannelResult> channelResults,
                                        SearchContext ctx) {
        long start = System.nanoTime();
        try {
            return doProcess(candidates, ctx);
        } finally {
            ctx.gateElapsedMs(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private List<RetrievedChunk> doProcess(List<RetrievedChunk> candidates, SearchContext ctx) {
        if (candidates.isEmpty()) {
            ctx.gateDecision("rejected");
            ctx.gateScore(null);
            return candidates;
        }
        RetrievedChunk best = candidates.stream()
                .filter(c -> c.gateScore() != null)
                .max(Comparator.comparingDouble(c -> c.gateScore()))
                .orElse(null);
        if (best == null) {
            // 无分可读：fail-open 放行（on-missing-score=allow），并留下归因标记
            log.warn("闸门[{}]候选 {} 条全部无 gateScore，fail-open 放行（降级标记 allow_no_score）",
                    ctx.query(), candidates.size());
            ctx.gateDecision("allow_no_score");
            ctx.gateScore(null);
            return candidates;
        }
        double max = best.gateScore();
        ctx.gateScore(max);
        if (max < props.getEvidence().getMinGateScore()) {
            log.info("闸门[{}]拦截：max gateScore={} < 阈值 {}，整批 {} 条丢弃、不调用 LLM",
                    ctx.query(), String.format("%.4f", max), props.getEvidence().getMinGateScore(), candidates.size());
            ctx.gateDecision("rejected");
            return List.of();
        }
        ctx.gateDecision("pass");
        return candidates;
    }
}
