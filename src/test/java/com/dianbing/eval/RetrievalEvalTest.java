package com.dianbing.eval;

import com.dianbing.testinfra.RequiresPostgres;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.infrastructure.llm.EmbeddingClient;
import com.dianbing.qa.retrieval.RetrievalEngine;
import com.dianbing.qa.retrieval.RetrievalOutcome;
import com.dianbing.qa.retrieval.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检索层评测（改造 v1：跑完整检索链 —— 双通道召回 → 去重 → RRF 融合 → 打分 → 批级闸门）。
 *
 * <p>只测检索、不调用 LLM，秒级、零 token，可当回归测试长期跑：
 * 每次改动阈值/权重/通道/分块策略后重跑，用同一套基准看指标涨跌。
 * 改写拆分不在此评测（那是生成链路的一部分，走 GenerationEvalTest）。
 *
 * <p>依赖 PostgreSQL(pgvector) 与 Ollama(bge-m3) 同时在线；任一不可用时整组自动跳过，
 * 不把「环境没起来」误报成「检索质量下降」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@RequiresPostgres
class RetrievalEvalTest {

    private static final String EVAL_SET = System.getProperty("eval.dataset", "/eval/campus_eval_v1.jsonl");

    @Autowired private JdbcClient db;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private RetrievalEngine retrievalEngine;

    @Autowired
    private RagProperties props;

    @Test
    void evaluateRetrieval() throws Exception {
        List<EvalSample> samples = EvalSupport.resolveEvidence(db, EvalSupport.load(EVAL_SET));
        EvalSupport.requireCurrentEvidence(db, samples);

        // 探针：Embedding 服务不可用就直接跳过，避免把环境问题算成评测失败
        try {
            embeddingClient.embed("探针");
        } catch (Exception e) {
            Assumptions.abort("Embedding 服务不可用（请确认 Ollama 已启动且已 pull "
                    + props.getEmbeddingModel() + "）：" + e.getMessage());
        }

        List<SampleResult> results = new ArrayList<>();
        for (EvalSample sample : samples) {
            // 检索评测用原问题单查询（不跑改写），隔离地度量检索链本身
            List<float[]> vectors = embeddingClient.embedBatch(List.of(sample.query()));
            RetrievalOutcome outcome = retrievalEngine.retrieve(List.of(sample.query()), vectors);
            results.add(score(sample, outcome));
        }

        printReport(results);
        writeFailureReport(results);
    }

    /** 单条样本打分：库内算检索质量，库外算护栏行为（闸门是否拦住） */
    private SampleResult score(EvalSample sample, RetrievalOutcome outcome) {
        List<String> retrieved = EvalSupport.refs(outcome.sources());
        Set<String> must = new LinkedHashSet<>(sample.expectedChunks());
        Set<String> nice = new LinkedHashSet<>(sample.expectedNice());

        Double hit1 = null;
        Double hit3 = null;
        Double hit5 = null;
        Double recallMust = null;
        Double recallInclusive = null;
        Double mrr = null;

        if (sample.requiresRag() && !must.isEmpty()) {
            hit1 = EvalSupport.hit(retrieved, must, 1);
            hit3 = EvalSupport.hit(retrieved, must, 3);
            hit5 = EvalSupport.hit(retrieved, must, 5);
            recallMust = EvalSupport.recall(retrieved, must, 5);
            mrr = EvalSupport.mrr(retrieved, must);
        }
        if (sample.requiresRag() && (!must.isEmpty() || !nice.isEmpty())) {
            Set<String> inclusive = new LinkedHashSet<>(must);
            inclusive.addAll(nice);
            recallInclusive = EvalSupport.recall(retrieved, inclusive, 5);
        }
        var attribution = RetrievalFailureAttribution.classify(sample, outcome);
        return new SampleResult(sample, outcome, retrieved, retrieved.isEmpty(), outcome.gateDecision(),
                attribution, hit1, hit3, hit5, recallMust, recallInclusive, mrr);
    }

    private void printReport(List<SampleResult> results) {
        List<SampleResult> inLibrary = results.stream().filter(r -> r.sample().requiresRag()).toList();
        List<SampleResult> outOfLibrary = results.stream().filter(r -> r.sample().strictRefusal()).toList();
        List<SampleResult> scopeCases = results.stream().filter(r -> r.sample().scopeWarning()).toList();

        System.out.println();
        System.out.println("================= 检索层评测（完整链路） =================");
        System.out.printf("评估集 %s｜样本 %d 条（库内 %d / 库外 %d）%n",
                EVAL_SET, results.size(), inLibrary.size(), outOfLibrary.size());
        System.out.printf("适用范围近邻样本 %d 条（单列观察，不纳入过召回分母）%n", scopeCases.size());
        System.out.printf("embedding=%s  topK=%d  candidateLimit=%d  min-similarity=%.2f  gate=%.2f  channels: vector=%s keyword=%s  fusion=%s%n",
                props.getEmbeddingModel(), props.getTopK(), props.getRetrieval().getCandidateLimit(),
                props.getMinSimilarity(), props.getEvidence().getMinGateScore(),
                props.getChannels().getVector().isEnabled(), props.getChannels().getKeyword().isEnabled(),
                props.getFusion().getStrategy());

        System.out.println();
        System.out.println("--- 检索质量（仅 requires_rag=true 且有期望切片）---");
        printMetric("Hit@1               ", EvalSupport.mean(inLibrary.stream().map(SampleResult::hit1).toList()));
        printMetric("Hit@3               ", EvalSupport.mean(inLibrary.stream().map(SampleResult::hit3).toList()));
        printMetric("Hit@5               ", EvalSupport.mean(inLibrary.stream().map(SampleResult::hit5).toList()));
        printMetric("Recall@5 (must)     ", EvalSupport.mean(inLibrary.stream().map(SampleResult::recallMust).toList()));
        printMetric("Recall@5 (inclusive)", EvalSupport.mean(inLibrary.stream().map(SampleResult::recallInclusive).toList()));
        printMetric("MRR                 ", EvalSupport.mean(inLibrary.stream().map(SampleResult::mrr).toList()));

        System.out.println();
        System.out.println("--- 闸门护栏（D4/D8）---");
        // 误拒：该召回却一条都没召回（闸门把它整批拦了）
        printMetric("误拒率 (库内召回为空)", EvalSupport.mean(inLibrary.stream().map(r -> r.rejected() ? 1.0 : 0.0).toList()));
        // 过召回：库里本来没答案，却召回了内容 —— 这些内容会被塞进 Prompt，是幻觉的来源
        printMetric("过召回率 (库外仍召回)", EvalSupport.mean(outOfLibrary.stream().map(r -> r.rejected() ? 0.0 : 1.0).toList()));
        System.out.printf("  适用范围近邻召回：%s%n", scopeCases.stream()
                .map(r -> r.sample().queryId() + "=" + r.retrieved().size() + "块").toList());
        System.out.printf("  闸门判定分布: pass=%d rejected=%d other=%s%n",
                results.stream().filter(r -> "pass".equals(r.gateDecision())).count(),
                results.stream().filter(r -> "rejected".equals(r.gateDecision())).count(),
                results.stream().map(SampleResult::gateDecision).filter(d -> d != null && !"pass".equals(d) && !"rejected".equals(d)).distinct().toList());

        List<SampleResult> failures = results.stream()
                .filter(r -> !"NONE".equals(r.attribution().category()))
                .toList();

        System.out.println();
        System.out.println("--- 失败明细（" + failures.size() + " 条）---");
        if (failures.isEmpty()) {
            System.out.println("  无");
        }
        for (SampleResult r : failures) {
            System.out.printf("  [%s] %s%n", r.sample().queryId(), r.sample().query());
            if (r.sample().requiresRag()) {
                System.out.printf("        期望 %s  实际 %s%n",
                        r.sample().expectedChunks(), r.retrieved());
            } else {
                System.out.printf("        库外问题却召回了 %s%n", r.retrieved());
            }
            System.out.printf("        归因 %s：%s%n", r.attribution().category(), r.attribution().explanation());
        }
        System.out.println("=========================================================");
    }

    private void writeFailureReport(List<SampleResult> results) throws Exception {
        List<Map<String, Object>> rows = results.stream()
                .filter(r -> !"NONE".equals(r.attribution().category()))
                .map(r -> RetrievalFailureAttribution.reportRow(r.sample(), r.outcome()))
                .toList();
        Path dir = Path.of("target", "eval-reports");
        Files.createDirectories(dir);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("retrieval-failure-attribution.json").toFile(), rows);
        System.out.printf("失败归因报告：%s（%d 条）%n",
                dir.resolve("retrieval-failure-attribution.json").toAbsolutePath(), rows.size());
    }

    private static void printMetric(String label, Double value) {
        System.out.printf("  %s  %s%n", label, value == null ? "—（无样本）" : String.format("%6.1f%%", value * 100));
    }

    /** 单条样本的评测结果；库内样本的指标为 null 表示该指标不适用（如库外样本没有期望切片） */
    private record SampleResult(
            EvalSample sample,
            RetrievalOutcome outcome,
            List<String> retrieved,
            boolean rejected,
            String gateDecision,
            RetrievalFailureAttribution.Result attribution,
            Double hit1,
            Double hit3,
            Double hit5,
            Double recallMust,
            Double recallInclusive,
            Double mrr) {
    }
}
