package com.pkb.eval;

import com.pkb.config.RagProperties;
import com.pkb.llm.EmbeddingClient;
import com.pkb.search.RetrievalEngine;
import com.pkb.search.RetrievalOutcome;
import com.pkb.search.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批级证据闸门阈值扫描（改造 v1：对应 D4，替代旧的 min-similarity SQL 过滤扫描）。
 *
 * <p>分两趟跑：
 * <ol>
 *   <li>把闸门与 SQL 过滤都关掉（gate mode=none、min-similarity=0），看每个问题真实的
 *       gateScore（余弦）分布 —— 库内样本的「信号」与库外样本的「噪声」分别有多高，两者是否重叠；</li>
 *   <li>SQL 过滤保持关闭，逐档扫 min-gate-score，每档重跑一次完整链路，
 *       观察 Hit@5 / Recall@5 / 误拒率 / 过召回率 怎么变化。</li>
 * </ol>
 *
 * <p>隔离设计：SQL 单条过滤（min-similarity）置 0，让「是否调 LLM」完全由批级闸门决定 ——
 * 这样扫出来的阈值就是闸门的定档依据（当前 0.60 即来自 §1.2 的扫描：信号下限 0.608 vs 噪声上限 0.578）。
 * 每个问题只向量化一次，所有档位复用同一个向量。跑完恢复原配置，不污染同 JVM 内的其他评测。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GateSweepTest {

    private static final String EVAL_SET = System.getProperty("eval.dataset", "/eval/campus_eval_v1.jsonl");

    @Autowired private JdbcClient db;

    /** 扫描档位：覆盖 bge-m3 余弦相似度在该语料上的常见区间 */
    private static final double[] THRESHOLDS = {0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75};

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private RetrievalEngine retrievalEngine;

    @Autowired
    private RagProperties props;

    @Test
    void sweepGateThreshold() throws Exception {
        List<EvalSample> samples = EvalSupport.resolveEvidence(db, EvalSupport.load(EVAL_SET));
        EvalSupport.requireCurrentEvidence(db, samples);

        try {
            embeddingClient.embed("探针");
        } catch (Exception e) {
            Assumptions.abort("Embedding 服务不可用（请确认 Ollama 已启动且已 pull "
                    + props.getEmbeddingModel() + "）：" + e.getMessage());
        }

        double originalGate = props.getEvidence().getMinGateScore();
        double originalMinSim = props.getMinSimilarity();
        String originalMode = props.getEvidence().getMode();
        try {
            // 每个问题只向量化一次，后续所有档位复用
            Map<String, float[]> vectors = new LinkedHashMap<>();
            for (EvalSample s : samples) {
                vectors.put(s.queryId(), embeddingClient.embed(s.query()));
            }

            // 第一趟：闸门与 SQL 过滤都关掉，拿到原始候选与分数
            props.getEvidence().setMode("none");
            props.setMinSimilarity(0.0);
            Map<String, List<Source>> raw = new LinkedHashMap<>();
            for (EvalSample s : samples) {
                raw.put(s.queryId(), retrieve(vectors.get(s.queryId()), s.query()));
            }
            printDistribution(samples, raw);

            // 第二趟：SQL 过滤保持关闭，逐档扫闸门阈值
            System.out.println();
            System.out.println("--- 闸门阈值扫描（topK=" + props.getTopK()
                    + "，SQL 单条过滤已关闭，是否调 LLM 完全由闸门决定）---");
            System.out.printf("%-7s %9s %17s %9s %11s   %s%n",
                    "阈值", "Hit@5", "Recall@5(must)", "误拒率", "过召回率", "丢证据的样本");
            props.getEvidence().setMode("batch");
            for (double threshold : THRESHOLDS) {
                props.getEvidence().setMinGateScore(threshold);
                Row row = evaluate(samples, vectors, raw, threshold);
                System.out.printf("%-7.2f %9s %17s %9s %11s   %s%s%n",
                        threshold,
                        pct(row.hit5()), pct(row.recallMust()),
                        pct(row.refusalRate()), pct(row.overRetrievalRate()),
                        row.losses().isEmpty() ? "—" : String.join(" ", row.losses()),
                        Math.abs(threshold - originalGate) < 1e-9 ? "   ← 当前配置" : "");
            }
        } finally {
            props.getEvidence().setMinGateScore(originalGate);
            props.getEvidence().setMode(originalMode);
            props.setMinSimilarity(originalMinSim);
        }
    }

    private List<Source> retrieve(float[] vector, String query) {
        RetrievalOutcome outcome = retrievalEngine.retrieve(List.of(query), List.of(vector));
        return outcome.sources();
    }

    /**
     * 打印信号/噪声分布：库内样本「期望切片的最佳相似度」升序 + 库外样本「Top-1 相似度」升序。
     * 两者交错即说明不存在能同时零误拒、零过召回的阈值。
     */
    private void printDistribution(List<EvalSample> samples, Map<String, List<Source>> raw) {
        System.out.println();
        System.out.println("--- gateScore（余弦）分布（闸门与 SQL 过滤都关掉后的原始 Top-" + props.getTopK() + "）---");

        List<double[]> signals = new ArrayList<>();   // [similarity, 序号]
        Map<Double, String> signalLabel = new LinkedHashMap<>();
        for (EvalSample s : samples) {
            if (!s.requiresRag()) {
                continue;
            }
            Set<String> expected = new LinkedHashSet<>(s.expectedChunks());
            expected.addAll(s.expectedNice());
            if (expected.isEmpty()) {
                continue;
            }
            List<Source> hits = raw.get(s.queryId());
            Double best = null;
            for (Source h : hits) {
                // 只看纯余弦分（keyword-only 命中的 ts_rank 量纲不同，会污染分布）
                if (h.vectorScore() == null) {
                    continue;
                }
                if (expected.contains(h.noteId() + ":" + h.seq())) {
                    best = best == null ? h.vectorScore() : Math.max(best, h.vectorScore());
                }
            }
            if (best == null) {
                System.out.printf("  %-5s %s  期望切片未进入 Top-K：调阈值也救不了，属于检索本身没命中%n",
                        s.queryId(), "     —— ");
                continue;
            }
            signals.add(new double[]{best, signals.size()});
            signalLabel.put(best, s.queryId() + "  " + s.query());
        }
        signals.sort(Comparator.comparingDouble(a -> a[0]));

        System.out.println("  信号（库内样本，期望切片的最佳相似度，升序）：");
        Double signalFloor = null;
        for (double[] sig : signals) {
            double sim = sig[0];
            if (signalFloor == null) {
                signalFloor = sim;
            }
            System.out.printf("     %.3f  %s%n", sim, signalLabel.get(sim));
        }

        List<double[]> noises = new ArrayList<>();
        Map<Double, String> noiseLabel = new LinkedHashMap<>();
        for (EvalSample s : samples) {
            if (s.requiresRag() || s.scopeWarning()) {
                continue;
            }
            List<Source> hits = raw.get(s.queryId());
            double top1 = hits.stream()
                    .map(Source::vectorScore)
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .max().orElse(0.0);
            noises.add(new double[]{top1, noises.size()});
            noiseLabel.put(top1, s.queryId() + "  " + s.query());
        }
        noises.sort(Comparator.comparingDouble(a -> a[0]));

        System.out.println("  噪声（库外样本，Top-1 相似度，升序）：");
        Double noiseCeiling = null;
        for (double[] noise : noises) {
            double sim = noise[0];
            noiseCeiling = sim;
            System.out.printf("     %.3f  %s%n", sim, noiseLabel.get(sim));
        }

        System.out.println();
        if (signalFloor != null && noiseCeiling != null) {
            System.out.printf("  信号下限 = %.3f    噪声上限 = %.3f%n", signalFloor, noiseCeiling);
            if (signalFloor > noiseCeiling) {
                System.out.printf("  → 存在完美分界：阈值取 %.3f ~ %.3f 之间可同时做到零误拒、零过召回%n",
                        noiseCeiling, signalFloor);
            } else {
                System.out.printf("  → 信号与噪声重叠 %.3f 个点，不存在完美阈值：只能按成本取权衡点%n",
                        noiseCeiling - signalFloor);
                System.out.println("     调高偏漏答（该答不答），调低偏过召回（库外也硬答）");
            }
        }
    }

    /** 在指定闸门阈值下重跑一遍完整链路，算这一档的四项指标，并记录相对基线丢了证据的样本 */
    private Row evaluate(List<EvalSample> samples, Map<String, float[]> vectors,
                         Map<String, List<Source>> raw, double threshold) {
        List<Double> hit5 = new ArrayList<>();
        List<Double> recallMust = new ArrayList<>();
        List<Double> refusal = new ArrayList<>();
        List<Double> overRetrieval = new ArrayList<>();
        List<String> losses = new ArrayList<>();

        for (EvalSample s : samples) {
            // 检索链内部按 props 里当前的闸门阈值判定，所以这一行就是生产行为
            List<Source> hits = retrieve(vectors.get(s.queryId()), s.query());
            List<String> refs = EvalSupport.refs(hits);
            if (s.requiresRag()) {
                Set<String> must = new LinkedHashSet<>(s.expectedChunks());
                if (!must.isEmpty()) {
                    hit5.add(EvalSupport.hit(refs, must, 5));
                    recallMust.add(EvalSupport.recall(refs, must, 5));
                    // 对比「闸门关闭」时的基线，看这一档是否把原本能召回的期望切片砍掉了
                    Set<String> baseline = new LinkedHashSet<>(EvalSupport.refs(raw.get(s.queryId())));
                    long lost = must.stream().filter(baseline::contains).count()
                            - must.stream().filter(refs::contains).count();
                    if (lost > 0) {
                        losses.add(s.queryId() + "(-" + lost + ")");
                    }
                }
                // 误拒：该召回却一条都没召回（闸门把整批拦了）
                refusal.add(refs.isEmpty() ? 1.0 : 0.0);
            } else if (s.strictRefusal()) {
                // 过召回：库里本来没答案，却召回了内容
                overRetrieval.add(refs.isEmpty() ? 0.0 : 1.0);
            }
        }
        return new Row(EvalSupport.mean(hit5), EvalSupport.mean(recallMust),
                EvalSupport.mean(refusal), EvalSupport.mean(overRetrieval), losses);
    }

    private static String pct(Double value) {
        return value == null ? "—" : String.format("%.1f%%", value * 100);
    }

    private record Row(Double hit5, Double recallMust, Double refusalRate,
                       Double overRetrievalRate, List<String> losses) {
    }
}
