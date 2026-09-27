package com.dianbing.eval;

import com.dianbing.qa.retrieval.RetrievalOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns retrieval traces into stable failure categories for regression reports. */
final class RetrievalFailureAttribution {
    private RetrievalFailureAttribution() {}

    record Result(String category, String explanation, List<String> rawChannelRefs,
                  List<String> postProcessorRefs, List<String> finalRefs) {}

    static Result classify(EvalSample sample, RetrievalOutcome outcome) {
        Set<String> expected = new LinkedHashSet<>(sample.expectedChunks());
        List<String> raw = refsAt(outcome.subQueryTrace(), "channels");
        List<String> passed = refsAt(outcome.subQueryTrace(), "passed");
        List<String> finals = EvalSupport.refs(outcome.sources());

        if (sample.strictRefusal() && !finals.isEmpty()) {
            return result("FALSE_POSITIVE_EVIDENCE", "库外问题仍有证据通过闸门", raw, passed, finals);
        }
        if (sample.scopeWarning() && !finals.isEmpty()) {
            return result("SCOPE_FILTER_LEAK", "显式适用范围与资料元数据不一致，但仍返回证据", raw, passed, finals);
        }
        if (!sample.requiresRag() || expected.isEmpty() || intersects(finals, expected)) {
            return result("NONE", "未发现检索失败", raw, passed, finals);
        }
        if (!intersects(raw, expected)) {
            return result("CHANNEL_RECALL_MISS", "向量与关键词原始候选均未召回必要证据", raw, passed, finals);
        }
        if (finals.isEmpty() && outcome.gateDecision() != null
                && outcome.gateDecision().startsWith("rejected")) {
            return result("EVIDENCE_GATE_REJECTION", "原始通道命中必要证据，但被证据闸门整体拦截", raw, passed, finals);
        }
        if (!intersects(passed, expected)) {
            return result("POST_PROCESSOR_DROP", "原始通道命中必要证据，但后置处理后丢失", raw, passed, finals);
        }
        return result("AGGREGATION_OR_TOPK_DROP", "必要证据通过子查询处理，但合并或 Top-K 截断后丢失",
                raw, passed, finals);
    }

    @SuppressWarnings("unchecked")
    private static List<String> refsAt(List<Map<String, Object>> trace, String stage) {
        Set<String> refs = new LinkedHashSet<>();
        for (Map<String, Object> item : trace) {
            Object value = item.get(stage);
            if ("channels".equals(stage) && value instanceof Map<?, ?> channels) {
                for (Object hits : channels.values()) collectRefs(hits, refs);
            } else {
                collectRefs(value, refs);
            }
        }
        return new ArrayList<>(refs);
    }

    private static void collectRefs(Object value, Set<String> refs) {
        if (!(value instanceof List<?> list)) return;
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("ref") != null) {
                refs.add(String.valueOf(map.get("ref")));
            }
        }
    }

    private static boolean intersects(List<String> actual, Set<String> expected) {
        return actual.stream().anyMatch(expected::contains);
    }

    private static Result result(String category, String explanation, List<String> raw,
                                 List<String> passed, List<String> finals) {
        return new Result(category, explanation, raw, passed, finals);
    }

    static Map<String, Object> reportRow(EvalSample sample, RetrievalOutcome outcome) {
        Result result = classify(sample, outcome);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("queryId", sample.queryId());
        row.put("query", sample.query());
        row.put("category", result.category());
        row.put("explanation", result.explanation());
        row.put("expected", sample.expectedChunks());
        row.put("rawChannelRefs", result.rawChannelRefs());
        row.put("postProcessorRefs", result.postProcessorRefs());
        row.put("finalRefs", result.finalRefs());
        row.put("gateDecision", outcome.gateDecision());
        row.put("gateScore", outcome.gateScore());
        row.put("trace", outcome.subQueryTrace());
        return row;
    }
}
