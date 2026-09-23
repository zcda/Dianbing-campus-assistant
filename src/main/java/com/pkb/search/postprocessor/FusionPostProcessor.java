package com.pkb.search.postprocessor;

import com.pkb.config.RagProperties;
import com.pkb.search.RetrievedChunk;
import com.pkb.search.channel.SearchChannelResult;
import com.pkb.search.channel.SearchContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合处理器（order=5，设计文档 D3）。
 *
 * <p>向量分（余弦）与关键词分（ts_rank）量纲不同、不可直接比较 —— RRF 只看名次：
 * {@code rrf(chunk) = Σ_channels weight_ch / (k + rank_ch + 1)}，k 默认 60（标准 RRF 常数）。
 * 名次取自各通道的原始列表（channelResults），不是去重后的 chunks ——
 * "被两路同时命中"自然加分，这正是 RRF 相对加权求和的核心优势。
 *
 * <p>单通道时跳过融合只做截断（candidate-limit），保证只开向量通道时行为与改造前一致；
 * 融合排序后按 candidate-limit 截断再送闸门，截断上限 > top-k 是"粗排扩池 + 闸门精选"两阶段分工的前提。
 */
@Component
public class FusionPostProcessor implements SearchResultPostProcessor {

    private final RagProperties props;

    public FusionPostProcessor(RagProperties props) {
        this.props = props;
    }

    @Override
    public String getName() { return "Fusion"; }

    @Override
    public int getOrder() { return 5; }

    @Override
    public boolean isEnabled(SearchContext ctx) {
        return "rrf".equalsIgnoreCase(props.getFusion().getStrategy());
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> candidates,
                                        List<SearchChannelResult> channelResults,
                                        SearchContext ctx) {
        int limit = props.getRetrieval().getCandidateLimit();
        if (channelResults.size() <= 1) {
            // 单通道：跳过融合，只做截断（保持通道原始名次）
            return candidates.size() <= limit ? candidates : new ArrayList<>(candidates.subList(0, limit));
        }

        int k = props.getFusion().getRrfK();
        Map<String, Double> rrf = new HashMap<>();
        for (SearchChannelResult channel : channelResults) {
            double weight = channelWeight(channel.type());
            for (int rank = 0; rank < channel.results().size(); rank++) {
                RetrievedChunk chunk = channel.results().get(rank);
                rrf.merge(chunk.ref(), weight / (k + rank + 1), Double::sum);
            }
        }
        for (RetrievedChunk chunk : candidates) {
            chunk.rrfScore(rrf.getOrDefault(chunk.ref(), 0.0));
        }
        List<RetrievedChunk> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(
                (RetrievedChunk c) -> c.rrfScore() == null ? 0.0 : c.rrfScore()).reversed());
        return sorted.size() <= limit ? sorted : new ArrayList<>(sorted.subList(0, limit));
    }

    private double channelWeight(com.pkb.search.channel.SearchChannelType type) {
        String key = type.name().toLowerCase();
        return props.getFusion().getChannelWeights().getOrDefault(key, 1.0);
    }
}
