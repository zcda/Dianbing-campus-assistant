package com.dianbing.qa.retrieval.postprocessor;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.qa.retrieval.RetrievedChunk;
import com.dianbing.qa.retrieval.channel.SearchChannelResult;
import com.dianbing.qa.retrieval.channel.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 打分填充处理器（order=8，设计文档 D4）：为闸门准备 gateScore。
 *
 * <p>取值优先级：rerankScore（将来接入精排）→ vectorScore（余弦，当前方案）→
 * 归一化 rrfScore（keyword-only 命中的兜底）→ null（fail-open 由闸门放行 + WARN）。
 *
 * <p>当前主路径用余弦是经过数据论证的：阈值扫描显示信号下限 0.608 > 噪声上限 0.578，
 * 存在完美分界区间，min-gate-score=0.60 就是按余弦分布定档的；
 * 归一化 rrf 量纲与余弦不同，只作为"向量分缺失"时的兜底（如切片向量化失败、纯关键词命中）。
 */
@Component
public class ScoreFillPostProcessor implements SearchResultPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ScoreFillPostProcessor.class);

    private final RagProperties props;

    public ScoreFillPostProcessor(RagProperties props) {
        this.props = props;
    }

    @Override
    public String getName() { return "ScoreFill"; }

    @Override
    public int getOrder() { return 8; }

    @Override
    public boolean isEnabled(SearchContext ctx) { return true; }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> candidates,
                                        List<SearchChannelResult> channelResults,
                                        SearchContext ctx) {
        double maxPossibleRrf = maxPossibleRrf(channelResults.size());
        for (RetrievedChunk chunk : candidates) {
            if (chunk.gateScore() != null) {
                continue;
            }
            if (chunk.vectorScore() != null) {
                chunk.gateScore(chunk.vectorScore());
            } else if (chunk.rrfScore() != null && maxPossibleRrf > 0) {
                chunk.gateScore(chunk.rrfScore() / maxPossibleRrf);
            }
            // 全部为 null：不填，闸门侧 fail-open 放行 + WARN（绝不制造假阴性）
        }
        if (candidates.stream().noneMatch(c -> c.gateScore() != null)) {
            log.warn("链[{}]候选 {} 条全部无可读分数（向量分与融合分均缺失），闸门将 fail-open", ctx.query(), candidates.size());
        }
        return candidates;
    }

    /** 理论最大 RRF 分（所有通道都排第 0 名）：用于把 rrfScore 归一化到 0~1 */
    private double maxPossibleRrf(int channelCount) {
        int k = props.getFusion().getRrfK();
        double max = 0;
        for (Map.Entry<String, Double> e : props.getFusion().getChannelWeights().entrySet()) {
            max += e.getValue();
        }
        // 权重表为空或异常时的保守值：每通道 1.0
        if (max <= 0) {
            max = Math.max(1, channelCount);
        }
        return max / (k + 1);
    }
}
