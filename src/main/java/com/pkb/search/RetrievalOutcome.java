package com.pkb.search;

import java.util.List;
import java.util.Map;

/**
 * 一次完整检索的结果（多子问题合并后）。
 *
 * <ul>
 *   <li>chunks / sources：闸门通过、合并去重、截断 topK 后的最终候选，index 与 sources 下标严格一致；</li>
 *   <li>gateDecision 聚合口径：任一子问题 pass → pass；否则任一 allow_no_score → allow_no_score；
 *       否则任一 rejected → rejected；闸门关闭 → disabled；</li>
 *   <li>sources 为空即"不该调 LLM"（无命中或闸门整批丢弃），拒答归因只看它，不依赖模型话术（D8）；</li>
 *   <li>channelCounts / subQueryTrace：通道归因与每子问题明细，供 meta 事件与调试接口复用（D11）。</li>
 * </ul>
 */
public record RetrievalOutcome(
        List<RetrievedChunk> chunks,
        List<Source> sources,
        boolean anyPassed,
        Double gateScore,
        String gateDecision,
        /** 合并去重后、截断 topK 前的候选总数（"pool by channel" vs "sent to gate"归因口径） */
        int candidateCount,
        Map<String, Integer> channelCounts,
        List<Map<String, Object>> subQueryTrace,
        long gateMs,
        long elapsedMs) {
}
