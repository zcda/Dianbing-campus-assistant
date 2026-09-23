package com.pkb.search;

import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import com.pkb.config.RagProperties;
import com.pkb.llm.EmbeddingClient;
import com.pkb.search.channel.SearchChannel;
import com.pkb.search.channel.SearchChannelResult;
import com.pkb.search.channel.SearchChannelType;
import com.pkb.search.channel.SearchContext;
import com.pkb.search.postprocessor.SearchResultPostProcessor;

/**
 * 检索引擎（设计文档 D1）：把"一个函数干完"的黑盒改成可插拔、可开关、可单独测试的链。
 *
 * <p>流程：子问题并行召回（各自独立扩池）→ 各自跑后置处理器链
 * （Dedup(1) → Fusion(5) → ScoreFill(8) → EvidenceGate(15)）→ 按 (noteId, seq) 全局去重合并
 * （排序键：命中子问题数 desc, rrfScore desc）→ 截断 topK → 重编号为 sources。
 *
 * <p>降级契约：任一处理器抛异常不得中断主链路，但必须 WARN + 记录"该环降级"标记
 * （降级环名出现在 trace.degraded，事后可归因）。
 */
@Service
public class RetrievalEngine implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEngine.class);

    private final List<SearchChannel> channels;
    private final List<SearchResultPostProcessor> registered;
    private final RagProperties props;
    private final EmbeddingClient embeddingClient;
    private final Executor ioExecutor;

    private List<SearchResultPostProcessor> processors;

    public RetrievalEngine(List<SearchChannel> channels,
                           List<SearchResultPostProcessor> processors,
                           RagProperties props,
                           EmbeddingClient embeddingClient,
                           @Qualifier("chatExecutor") Executor ioExecutor) {
        this.channels = channels;
        this.registered = processors;
        this.props = props;
        this.embeddingClient = embeddingClient;
        this.ioExecutor = ioExecutor;
    }

    @Override
    public void afterPropertiesSet() {
        this.processors = registered.stream()
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();
        log.info("检索链: channels={} processors={}",
                channels.stream().filter(SearchChannel::isEnabled).map(c -> c.type().name()).toList(),
                processors.stream().map(SearchResultPostProcessor::getName).toList());

        // 启动期 fail-fast（D4-5）：闸门开着却没有任何 scorer 可用 → 宁可起不来，也不要静默空转。
        // 这正是 F1 的解药：MVP-0 的护栏失效是"配置错了但没人知道"，新设计把这类问题变成"启动即报错"。
        boolean vectorEnabled = props.getChannels().getVector().isEnabled();
        if (props.getEvidence().getMinGateScore() > 0
                && "batch".equalsIgnoreCase(props.getEvidence().getMode())
                && !vectorEnabled) {
            throw new IllegalStateException(("""
                    配置矛盾：rag.evidence.min-gate-score=%.2f 要求闸门生效，但没有任何可用 scorer\
                    （channels.vector.enabled=false，rerank 未接入）。闸门会「无分可读、恒放行」等于静默空转。\
                    请开启 vector 通道，或将 evidence.min-gate-score 设为 <=0 关闭闸门。""")
                    .formatted(props.getEvidence().getMinGateScore()));
        }
        if (props.getRetrieval().getCandidateLimit() <= props.getTopK()) {
            throw new IllegalStateException(("""
                    配置矛盾：rag.retrieval.candidate-limit(%d) 必须 > top-k(%d) \
                    —— 这是「粗排扩池 + 闸门精选」两阶段分工能成立的前提。""")
                    .formatted(props.getRetrieval().getCandidateLimit(), props.getTopK()));
        }
    }

    /**
     * 多子问题检索入口（D6）：每个子问题各自跑通道 + 各自 RRF + 各自闸门，
     * 通过闸门的候选再合并去重、排序、截断 topK。
     */
    public RetrievalOutcome retrieve(List<String> subQueries, List<float[]> subVectors) {
        return retrieve(subQueries, subVectors, String.join(" ", subQueries));
    }

    public RetrievalOutcome retrieve(List<String> subQueries, List<float[]> subVectors, String originalQuestion) {
        long start = System.nanoTime();
        if (subQueries.size() == 1) {
            SubResult result = retrieveForSubQuery(subQueries.get(0), subVectors.get(0), originalQuestion);
            return checkQuestionFocus(aggregate(List.of(result), System.nanoTime() - start), originalQuestion, start);
        }
        List<CompletableFuture<SubResult>> futures = new ArrayList<>();
        for (int i = 0; i < subQueries.size(); i++) {
            String query = subQueries.get(i);
            float[] vector = subVectors.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> retrieveForSubQuery(query, vector, originalQuestion), ioExecutor));
        }
        List<SubResult> results = futures.stream()
                .map(f -> f.join())
                .toList();
        return checkQuestionFocus(aggregate(results, System.nanoTime() - start), originalQuestion, start);
    }

    private RetrievalOutcome checkQuestionFocus(RetrievalOutcome outcome, String question, long start) {
        if (outcome.sources().isEmpty() || !"batch".equalsIgnoreCase(props.getEvidence().getMode())
                || props.getEvidence().getMinFocusScore() <= 0) return outcome;
        var focus = AcademicQuestionFocus.extract(question);
        if (focus.isEmpty()) return outcome;
        outcome = checkFocusedEvidence(outcome, focus.get(), props.getEvidence().getMinFocusScore(),
                "focus", start);
        if (outcome.sources().isEmpty() || props.getEvidence().getMinAnswerTargetScore() <= 0) return outcome;
        var target = AcademicQuestionFocus.answerTarget(focus.get());
        if (target.isEmpty()) return outcome;
        return checkFocusedEvidence(outcome, target.get(), props.getEvidence().getMinAnswerTargetScore(),
                "answer_target", start);
    }

    private RetrievalOutcome checkFocusedEvidence(RetrievalOutcome outcome, String query, double threshold,
                                                   String kind, long start) {
        try {
            SubResult focused = retrieveForSubQuery(query, embeddingClient.embed(query), query);
            var vectorScore = focused.channelResults().stream()
                    .filter(result -> result.type() == SearchChannelType.VECTOR)
                    .flatMap(result -> result.results().stream())
                    .map(RetrievedChunk::vectorScore)
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue).max();
            if (vectorScore.isEmpty() || !focused.degraded().isEmpty()) {
                log.warn("证据检查[{}:{}]无可靠分数，fail-open 放行", kind, query);
                return outcome;
            }
            double score = vectorScore.getAsDouble();
            List<Map<String, Object>> trace = new ArrayList<>(outcome.subQueryTrace());
            Map<String, Object> focusTrace = new LinkedHashMap<>();
            focusTrace.put("query", query);
            boolean rejected = score < threshold;
            focusTrace.put("gateDecision", rejected ? "rejected_" + kind : "pass_" + kind);
            focusTrace.put("gateScore", score);
            focusTrace.put("threshold", threshold);
            trace.add(focusTrace);
            if (!rejected) return new RetrievalOutcome(outcome.chunks(), outcome.sources(), outcome.anyPassed(),
                    outcome.gateScore(), outcome.gateDecision(), outcome.candidateCount(), outcome.channelCounts(),
                    trace, outcome.gateMs() + focused.gateMs(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            log.info("证据检查[{}:{}]拦截：score={} < 阈值 {}", kind, query, score, threshold);
            return new RetrievalOutcome(List.of(), List.of(), false, outcome.gateScore(), "rejected_" + kind,
                    outcome.candidateCount(), outcome.channelCounts(), trace, outcome.gateMs() + focused.gateMs(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (Exception e) {
            log.warn("证据检查[{}:{}]失败，fail-open 放行: {}", kind, query, e.toString());
            return outcome;
        }
    }

    /** 单个子问题的完整链路：通道召回 → 后置处理器链（异常不中断主链路，记降级标记） */
    SubResult retrieveForSubQuery(String query, float[] vector) {
        return retrieveForSubQuery(query, vector, query);
    }

    SubResult retrieveForSubQuery(String query, float[] vector, String originalQuestion) {
        SearchContext ctx = new SearchContext(query, vector, originalQuestion);

        List<SearchChannelResult> channelResults = new ArrayList<>();
        for (SearchChannel channel : channels) {
            if (!channel.isEnabled()) {
                continue;
            }
            try {
                channelResults.add(channel.search(ctx));
            } catch (Exception e) {
                log.warn("通道[{}]查询[{}]召回失败，该通道降级: {}", channel.type(), query, e.toString());
            }
        }

        // 初始候选 = 各通道原始命中的扁平拼接（同一 chunk 可能重复出现，由 Dedup 处理器合并）
        List<RetrievedChunk> candidates = new ArrayList<>();
        for (SearchChannelResult channel : channelResults) {
            candidates.addAll(channel.results());
        }

        List<String> degraded = new ArrayList<>();
        for (SearchResultPostProcessor processor : processors) {
            if (!processor.isEnabled(ctx)) {
                continue;
            }
            try {
                candidates = processor.process(candidates, channelResults, ctx);
            } catch (Exception e) {
                degraded.add(processor.getName());
                log.warn("处理器[{}]查询[{}]执行失败，该环降级（结果继续返回）: {}",
                        processor.getName(), query, e.toString());
            }
        }
        return new SubResult(query, List.copyOf(candidates), ctx.gateDecision(), ctx.gateScore(),
                ctx.gateElapsedMs(), channelResults, degraded);
    }

    /** 合并各子问题结果：全局去重 → (命中子问题数 desc, rrfScore desc) → 截断 topK → 重编号 */
    private RetrievalOutcome aggregate(List<SubResult> subResults, long elapsedNanos) {
        Map<String, Merged> merged = new LinkedHashMap<>();
        for (SubResult sub : subResults) {
            java.util.Set<String> seenInSubQuery = new java.util.HashSet<>();
            for (RetrievedChunk chunk : sub.candidates()) {
                Merged item = merged.computeIfAbsent(chunk.ref(), r -> new Merged(chunk));
                if (seenInSubQuery.add(chunk.ref())) item.subQueryHits++;
                if (chunk.bestScore() > item.chunk.bestScore()) item.chunk = chunk;
            }
        }
        List<Merged> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.<Merged>comparingInt(m -> -m.subQueryHits)
                .thenComparingDouble(m -> -m.chunk.bestScore()));
        List<RetrievedChunk> top = sorted.stream()
                .limit(props.getTopK())
                .map(m -> m.chunk)
                .toList();
        List<Source> sources = IntStream.rangeClosed(1, top.size())
                .mapToObj(i -> Source.of(i, top.get(i - 1)))
                .toList();

        boolean anyPassed = subResults.stream().anyMatch(s -> !s.candidates().isEmpty());
        java.util.OptionalDouble maxGate = subResults.stream()
                .map(SubResult::gateScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max();
        Double gateScore = maxGate.isPresent() ? maxGate.getAsDouble() : null;
        String gateDecision = aggregateDecision(subResults);

        Map<String, Integer> channelCounts = new LinkedHashMap<>();
        for (SubResult sub : subResults) {
            for (SearchChannelResult channel : sub.channelResults()) {
                channelCounts.merge(channel.type().name().toLowerCase(), channel.results().size(), Integer::sum);
            }
        }
        long gateMs = subResults.stream().mapToLong(SubResult::gateMs).sum();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return new RetrievalOutcome(top, sources, anyPassed, gateScore, gateDecision,
                merged.size(), channelCounts, trace(subResults), gateMs, elapsedMs);
    }

    private String aggregateDecision(List<SubResult> subResults) {
        boolean rejected = false;
        boolean allowNoScore = false;
        for (SubResult sub : subResults) {
            if ("pass".equals(sub.gateDecision())) {
                return "pass";
            }
            if ("allow_no_score".equals(sub.gateDecision())) {
                allowNoScore = true;
            }
            if ("rejected".equals(sub.gateDecision())) {
                rejected = true;
            }
        }
        if (allowNoScore) {
            return "allow_no_score";
        }
        return rejected ? "rejected" : "disabled";
    }

    /** 每个子问题的归因明细（/api/debug/retrieval 与 SSE meta 共用） */
    private List<Map<String, Object>> trace(List<SubResult> subResults) {
        List<Map<String, Object>> trace = new ArrayList<>();
        for (SubResult sub : subResults) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("query", sub.query());
            item.put("gateDecision", sub.gateDecision());
            item.put("gateScore", sub.gateScore());
            Map<String, Object> byChannel = new LinkedHashMap<>();
            for (SearchChannelResult channel : sub.channelResults()) {
                List<Map<String, Object>> hits = new ArrayList<>();
                for (int rank = 0; rank < channel.results().size(); rank++) {
                    RetrievedChunk c = channel.results().get(rank);
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("ref", c.ref());
                    hit.put("noteTitle", c.noteTitle());
                    hit.put("score", c.vectorScore() != null ? c.vectorScore() : c.keywordScore());
                    hits.add(hit);
                }
                byChannel.put(channel.type().name().toLowerCase(), hits);
            }
            item.put("channels", byChannel);
            item.put("passed", sub.candidates().stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ref", c.ref());
                m.put("rrfScore", c.rrfScore());
                m.put("gateScore", c.gateScore());
                m.put("vectorScore", c.vectorScore());
                m.put("keywordScore", c.keywordScore());
                m.put("hitChannels", c.hitChannels().stream().map(Enum::name).map(String::toLowerCase).toList());
                return m;
            }).toList());
            if (!sub.degraded().isEmpty()) {
                item.put("degraded", sub.degraded());
            }
            trace.add(item);
        }
        return trace;
    }

    /** 单个子问题的链路执行结果 */
    record SubResult(String query,
                     List<RetrievedChunk> candidates,
                     String gateDecision,
                     Double gateScore,
                     long gateMs,
                     List<SearchChannelResult> channelResults,
                     List<String> degraded) {
    }

    private static final class Merged {
        RetrievedChunk chunk;
        int subQueryHits;

        Merged(RetrievedChunk chunk) {
            this.chunk = chunk;
        }
    }
}
