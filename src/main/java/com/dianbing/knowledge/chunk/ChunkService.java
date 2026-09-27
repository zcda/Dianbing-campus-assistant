package com.dianbing.knowledge.chunk;

import com.dianbing.infrastructure.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 切片服务（改造 v1，设计文档 D5）：分块参数从硬编码常量升级为「预算」+ 策略可插拔。
 *
 * <ul>
 *   <li>预算对象启动期构造并校验（0 ≤ overlap < maxChars、maxChars ≤ 8192、toleranceFactor ∈ [1,8]），
 *       非法配置直接启动失败并提示合法区间 —— "否则超长块要到嵌入那一步才炸"；</li>
 *   <li>策略由 rag.chunk.strategy 选择（heading | fixed），支持 A/B 对比实验；
 *       切片参数变更后需重建索引（POST /api/index/rebuild）才能反映到库内切片。</li>
 * </ul>
 */
@Service
public class ChunkService {

    private final Map<String, ChunkStrategy> strategies;
    private final RagProperties props;

    private ChunkBudget budget;
    private ChunkStrategy strategy;

    public ChunkService(List<ChunkStrategy> strategies, RagProperties props) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(ChunkStrategy::name, Function.identity()));
        this.props = props;
    }

    @PostConstruct
    void initBudget() {
        RagProperties.Chunk cfg = props.getChunk();
        this.budget = ChunkBudget.of(cfg.getMaxChars(), cfg.getOverlapChars(), cfg.getToleranceFactor());
        this.strategy = strategies.get(cfg.getStrategy() == null ? "heading" : cfg.getStrategy().toLowerCase());
        if (strategy == null) {
            throw new IllegalStateException("rag.chunk.strategy=%s 不存在，可选值：%s"
                    .formatted(cfg.getStrategy(), strategies.keySet()));
        }
    }

    /** 当前生效的预算（评测/调试接口展示用） */
    public ChunkBudget budget() {
        return budget;
    }

    public List<String> split(String content) {
        return strategy.split(content, budget);
    }
}
