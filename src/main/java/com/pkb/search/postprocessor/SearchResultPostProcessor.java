package com.pkb.search.postprocessor;

import com.pkb.search.RetrievedChunk;
import com.pkb.search.channel.SearchChannelResult;
import com.pkb.search.channel.SearchContext;

import java.util.List;

/**
 * 检索后置处理器扩展点（设计文档 D1 责任链）。
 *
 * <p>链上约定 order：Dedup(1) → Fusion(5) → ScoreFill(8) → EvidenceGate(15)。
 * 入参同时给"去重后的候选集"与"各通道原始名次列表"——RRF 需要原始名次（见 FusionPostProcessor）。
 *
 * <p>实现约定：
 * <ul>
 *   <li>每一环都能独立关掉（isEnabled 返回 false），关掉后链路仍然正确（fail-open）；</li>
 *   <li>任一处理器抛异常不得中断主链路（由 RetrievalEngine 捕获），但必须 WARN + 记录降级标记。</li>
 * </ul>
 */
public interface SearchResultPostProcessor {

    String getName();

    int getOrder();

    /** 本环开关：关闭后引擎直接跳过这一环 */
    boolean isEnabled(SearchContext ctx);

    /** 对候选集做一次变换（去重/融合/打分/闸门），返回新的候选列表 */
    List<RetrievedChunk> process(List<RetrievedChunk> candidates,
                                 List<SearchChannelResult> channelResults,
                                 SearchContext ctx);
}
