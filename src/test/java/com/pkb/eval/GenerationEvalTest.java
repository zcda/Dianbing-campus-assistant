package com.pkb.eval;

import com.pkb.testinfra.RequiresPostgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pkb.config.RagProperties;
import com.pkb.conversation.Conversation;
import com.pkb.conversation.ConversationService;
import com.pkb.llm.ChatClient;
import com.pkb.llm.EmbeddingClient;
import com.pkb.rag.CitationCheck;
import com.pkb.rag.RagService;
import com.pkb.search.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;

/**
 * 生成层评测（设计文档 D7，补上 MVP-0 唯一的真正缺口 F7）：
 * 录制与评分分离 —— 录制阶段只调生产链路（/api/chat 同款 RagService.chat），
 * 把 (query_id, question, 子问题, 各通道命中, sources, answer, done_reason, timings) 原样写 eval_record；
 * 评分阶段纯计算零 token（检索类指标 + 角标可映射率 + 拒答准确率），LLM-as-judge 仅用于答案正确率。
 * 比较历史 run 时要求语料与评测集指纹一致；更换模型或参数仍需重新录制。
 *
 * <p>三个指标（定义无歧义）：
 * <ul>
 *   <li>角标可映射率 = 可映射角标数 / 角标总数，纯字符串解析，角标总数为 0 单列计数；</li>
 *   <li>拒答准确率 = 无依据样本中 done.reason == "no_sources" 的占比；这是闸门拒答，不代表最终文字没有拒答；</li>
 *   <li>答案正确率 = judge（与生产不同的模型）判 0/1/2 的均值 + 完全正确率。</li>
 * </ul>
 *
 * <p>用法：mvn test -Dtest=GenerationEvalTest -Deval.tag=rrf-keyword [-Deval.limit=10] [-Deval.judge=false]
 * 报告打印到控制台并写入 target/eval-reports/&lt;tag&gt;.md；若存在同语料指纹的 campus-baseline 历史 run，自动输出 diff 表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@RequiresPostgres
class GenerationEvalTest {

    private static final String EVAL_SET = System.getProperty("eval.dataset", "/eval/campus_eval_v1.jsonl");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private int evaluatedSampleCount;
    private String evaluatedQueryIds;

    @Autowired
    private RagService ragService;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private EmbeddingClient embeddingClient;
    @Autowired
    private ChatClient chatClient;
    @Autowired
    private JdbcClient db;
    @Autowired
    private RagProperties props;

    @Test
    void recordAndScore() throws Exception {
        List<EvalSample> samples = EvalSupport.load(EVAL_SET);
        String selectedIds = System.getProperty("eval.queryIds", "").strip();
        if (!selectedIds.isEmpty()) {
            Set<String> requested = Set.of(selectedIds.split("\\s*,\\s*"));
            samples = samples.stream().filter(s -> requested.contains(s.queryId())).toList();
            if (samples.size() != requested.size()) {
                throw new IllegalArgumentException("eval.queryIds 中有不存在或重复的题号：" + selectedIds);
            }
        }
        String tag = System.getProperty("eval.tag", "gen-" + System.currentTimeMillis());
        Integer limit = Integer.getInteger("eval.limit");
        boolean judgeEnabled = Boolean.parseBoolean(System.getProperty("eval.judge", "true"));
        if (limit != null && limit > 0 && limit < samples.size()) {
            samples = samples.subList(0, limit);
        }
        samples = EvalSupport.resolveEvidence(db, samples);
        evaluatedSampleCount = samples.size();
        evaluatedQueryIds = String.join(",", samples.stream().map(EvalSample::queryId).toList());
        EvalSupport.requireCurrentEvidence(db, samples);

        // 探针：环境不可用即跳过（R6：不把环境问题误报成质量下降）
        try {
            embeddingClient.embed("探针");
        } catch (Exception e) {
            Assumptions.abort("Embedding 服务不可用（请确认 Ollama 已启动且已 pull "
                    + props.getEmbeddingModel() + "）：" + e.getMessage());
        }

        System.out.printf("%n===== 生成层评测：阶段 1 录制（tag=%s，样本 %d 条）=====%n", tag, samples.size());
        long runId = insertRun(tag);
        List<Recorded> recorded = new ArrayList<>();
        for (EvalSample sample : samples) {
            recorded.add(recordSample(runId, sample));
        }

        System.out.printf("%n===== 阶段 2 评分（检索类指标纯计算零 token）=====%n");
        Map<String, Object> metrics = score(runId, recorded, judgeEnabled);
        writeMetrics(runId, metrics);
        report(runId, tag, recorded, metrics);
    }

    // ==================== 阶段 1：录制 ====================

    private long insertRun(String tag) throws Exception {
        return db.sql("""
                        INSERT INTO eval_run(tag, config)
                        VALUES (:tag, CAST(:config AS jsonb))
                        RETURNING id
                        """)
                .param("tag", tag)
                .param("config", mapper.writeValueAsString(configSnapshot()))
                .query(Long.class)
                .single();
    }

    /** 逐条调用生产链路（RagService.chat 与 /api/chat 完全同款），结果原样落 eval_record */
    private Recorded recordSample(long runId, EvalSample sample) {
        Conversation conversation = conversations.create();
        try {
            StringBuilder answer = new StringBuilder();
            List<Map<String, Object>> events = new ArrayList<>();
            RagService.ChatOutcome outcome = ragService.chat(conversation.id(), sample.query(),
                    (event, data) -> {
                        events.add(Map.of("event", event, "data", String.valueOf(data)));
                        if ("delta".equals(event) && data instanceof Map<?, ?> map
                                && map.get("text") instanceof String text) {
                            answer.append(text);
                        }
                    });
            insertRecord(runId, sample, outcome);
            System.out.printf("  [%s] %-6s sources=%d answerChars=%d%n",
                    sample.queryId(), outcome.doneReason(),
                    outcome.sources().size(), outcome.answer() == null ? 0 : outcome.answer().length());
            return new Recorded(sample, outcome);
        } catch (Exception e) {
            // 环境类错误（LLM 挂了等）：录制为 error，评分阶段按 SKIPPED 口径处理，不算质量下降
            RagService.ChatOutcome failed = new RagService.ChatOutcome("-", "error",
                    null, List.of(), Map.of(), 0, null, Map.of());
            insertErrorRecord(runId, sample, e.toString());
            System.out.printf("  [%s] error  %s%n", sample.queryId(), e.toString());
            return new Recorded(sample, failed);
        } finally {
            conversations.delete(conversation.id());
        }
    }

    private void insertRecord(long runId, EvalSample sample, RagService.ChatOutcome outcome) {
        try {
            db.sql("""
                            INSERT INTO eval_record(run_id, query_id, question, sub_questions, retrieved,
                                                    answer, done_reason, timings)
                            VALUES (:runId, :queryId, :question, CAST(:subs AS jsonb), CAST(:retrieved AS jsonb),
                                    :answer, :doneReason, CAST(:timings AS jsonb))
                            """)
                    .param("runId", runId)
                    .param("queryId", sample.queryId())
                    .param("question", sample.query())
                    .param("subs", toJson(outcome.meta().get("subQuestions")))
                    .param("retrieved", toJson(outcome.sources()))
                    .param("answer", outcome.answer())
                    .param("doneReason", outcome.doneReason())
                    .param("timings", toJson(outcome.timings()))
                    .update();
        } catch (Exception e) {
            throw new IllegalStateException("eval_record 落库失败: " + e.getMessage(), e);
        }
    }

    private void insertErrorRecord(long runId, EvalSample sample, String error) {
        try {
            db.sql("""
                            INSERT INTO eval_record(run_id, query_id, question, retrieved, answer, done_reason, timings)
                            VALUES (:runId, :queryId, :question, CAST('[]' AS jsonb), NULL, 'error', CAST(:err AS jsonb))
                            """)
                    .param("runId", runId)
                    .param("queryId", sample.queryId())
                    .param("question", sample.query())
                    .param("err", "{\"error\":" + toJson(error) + "}")
                    .update();
        } catch (Exception e) {
            throw new IllegalStateException("eval_record 落库失败: " + e.getMessage(), e);
        }
    }

    private String toJson(Object value) throws Exception {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    // ==================== 阶段 2：评分 ====================

    private Map<String, Object> score(long runId, List<Recorded> recorded, boolean judgeEnabled) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        List<Recorded> completed = recorded.stream().filter(r -> !"error".equals(r.outcome().doneReason())).toList();
        List<Recorded> inLibrary = completed.stream().filter(r -> r.sample().requiresRag()).toList();
        List<Recorded> outOfLibrary = completed.stream().filter(r -> r.sample().strictRefusal()).toList();
        List<Recorded> scopeCases = completed.stream().filter(r -> r.sample().scopeWarning()).toList();
        metrics.put("已完成样本数", completed.size());
        metrics.put("错误样本数", recorded.size() - completed.size());
        metrics.put("适用范围待复核样本数", scopeCases.size());

        // —— 检索类（纯计算零 token，与 RetrievalEvalTest 同一套口径）——
        List<Double> hit5 = new ArrayList<>();
        List<Double> recallMust = new ArrayList<>();
        List<Double> mrr = new ArrayList<>();
        List<Double> refusal = new ArrayList<>();
        for (Recorded r : inLibrary) {
            Set<String> must = new LinkedHashSet<>(r.sample().expectedChunks());
            List<String> refs = EvalSupport.refs(r.outcome().sources());
            if (!must.isEmpty()) {
                hit5.add(EvalSupport.hit(refs, must, 5));
                recallMust.add(EvalSupport.recall(refs, must, 5));
                mrr.add(EvalSupport.mrr(refs, must));
            }
            refusal.add(refs.isEmpty() ? 1.0 : 0.0);
        }
        metrics.put("Hit@5", EvalSupport.mean(hit5));
        metrics.put("Recall@5(must)", EvalSupport.mean(recallMust));
        metrics.put("MRR", EvalSupport.mean(mrr));
        metrics.put("误拒率", EvalSupport.mean(refusal));

        List<Double> overRetrieval = new ArrayList<>();
        for (Recorded r : outOfLibrary) {
            overRetrieval.add(EvalSupport.refs(r.outcome().sources()).isEmpty() ? 0.0 : 1.0);
        }
        metrics.put("过召回率", EvalSupport.mean(overRetrieval));

        // —— 生成类 1：拒答准确率（看 done.reason，不调用任何模型）——
        List<Double> refusalAccuracy = new ArrayList<>();
        for (Recorded r : outOfLibrary) {
            refusalAccuracy.add("no_sources".equals(r.outcome().doneReason()) ? 1.0 : 0.0);
        }
        metrics.put("拒答准确率", EvalSupport.mean(refusalAccuracy));

        // —— 生成类 2：只能自动判断角标是否映射到来源，不能据此声称来源支撑结论 ——
        List<Double> coverage = new ArrayList<>();
        List<Double> coverageNoZeroCitations = new ArrayList<>();
        int zeroCitationAnswers = 0;
        for (Recorded r : inLibrary) {
            String answer = r.outcome().answer();
            if (answer == null || !"ok".equals(r.outcome().doneReason())) {
                continue;
            }
            CitationCheck.Result c = r.outcome().citations() != null
                    ? r.outcome().citations()
                    : CitationCheck.check(answer, r.outcome().sources().size());
            coverage.add(c.coverage());
            if (c.total() == 0) {
                zeroCitationAnswers++;
            } else {
                coverageNoZeroCitations.add(c.coverage());
            }
        }
        metrics.put("角标可映射率", EvalSupport.mean(coverage));
        metrics.put("角标可映射率(排除零角标)", EvalSupport.mean(coverageNoZeroCitations));
        metrics.put("零角标答案数", zeroCitationAnswers);

        // —— 生成类 2.5：人工金标准事实的确定性检查（不调用模型）——
        List<Double> requiredFactCoverage = new ArrayList<>();
        List<Double> forbiddenFactClean = new ArrayList<>();
        for (Recorded r : inLibrary) {
            if (!"ok".equals(r.outcome().doneReason()) || r.outcome().answer() == null) continue;
            String answer = compact(r.outcome().answer());
            if (!r.sample().requiredFacts().isEmpty()) {
                long found = r.sample().requiredFacts().stream()
                        .filter(fact -> java.util.Arrays.stream(fact.split("\\|"))
                                .anyMatch(variant -> answer.contains(compact(variant)))).count();
                requiredFactCoverage.add((double) found / r.sample().requiredFacts().size());
            }
            if (!r.sample().forbiddenFacts().isEmpty()) {
                long forbidden = r.sample().forbiddenFacts().stream()
                        .filter(fact -> answer.contains(compact(fact))).count();
                forbiddenFactClean.add(forbidden == 0 ? 1.0 : 0.0);
            }
        }
        metrics.put("必要事实字面覆盖率", EvalSupport.mean(requiredFactCoverage));
        metrics.put("禁止事实零命中率", EvalSupport.mean(forbiddenFactClean));

        List<Double> dateValid = new ArrayList<>();
        List<Double> scopeValid = new ArrayList<>();
        List<Long> ttft = new ArrayList<>();
        for (Recorded r : inLibrary) {
            if (!"ok".equals(r.outcome().doneReason())) continue;
            Object firstToken = r.outcome().timings().get("llmFirstTokenMs");
            if (firstToken instanceof Number n && n.longValue() > 0) ttft.add(n.longValue());
            for (Source source : r.outcome().sources()) {
                if (source.rule() == null) {
                    dateValid.add(0.0);
                    if (r.sample().expectedAudience() != null) scopeValid.add(0.0);
                    continue;
                }
                var rule = source.rule();
                boolean current = (rule.effectiveFrom() == null || !rule.effectiveFrom().isAfter(LocalDate.now()))
                        && (rule.effectiveTo() == null || !rule.effectiveTo().isBefore(LocalDate.now()));
                dateValid.add(current ? 1.0 : 0.0);
                if (r.sample().expectedAudience() != null || r.sample().expectedAcademicYear() != null) {
                    boolean audience = r.sample().expectedAudience() == null
                            || r.sample().expectedAudience().equals(rule.audience())
                            || "全体学生".equals(rule.audience());
                    boolean year = r.sample().expectedAcademicYear() == null
                            || rule.academicYear() == null
                            || r.sample().expectedAcademicYear().equals(rule.academicYear());
                    scopeValid.add(audience && year ? 1.0 : 0.0);
                }
            }
        }
        metrics.put("来源日期有效率", EvalSupport.mean(dateValid));
        metrics.put("来源适用范围匹配率", EvalSupport.mean(scopeValid));
        metrics.put("TTFT_P50_ms", percentile(ttft, 0.50));
        metrics.put("TTFT_P95_ms", percentile(ttft, 0.95));

        // —— 生成类 3：答案正确率（LLM-as-judge，模型与生产不同）——
        if (judgeEnabled) {
            judge(runId, recorded, metrics);
        } else {
            metrics.put("答案正确率(judge归一化均值)", null);
            System.out.println("  judge 已关闭（-Deval.judge=false），答案正确率 SKIPPED");
        }
        return metrics;
    }

    private Long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return null;
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(p * sorted.size()) - 1);
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "")
                .replace("。", "").replace("，", "").replace(",", "");
    }

    private void judge(long runId, List<Recorded> recorded, Map<String, Object> metrics) {
        String judgeModel = props.getEval().getJudgeModel();
        List<Double> scores = new ArrayList<>();
        List<Double> correctScores = new ArrayList<>(); // judge=2 视为完全正确
        List<String> judgeErrors = new ArrayList<>();
        try {
            // judge 探针：模型没 pull 就整体 SKIPPED（R6）
            chatClient.complete(judgeModel, "你是评审员。", "回复数字 1", 0.0, 1.0, 10_000);
        } catch (Exception e) {
            System.out.printf("  judge 模型 %s 不可用，答案正确率 SKIPPED：%s%n", judgeModel, e.getMessage());
            metrics.put("答案正确率(judge归一化均值)", null);
            return;
        }
        for (Recorded r : recorded) {
            if (!r.sample().requiresRag() || !"ok".equals(r.outcome().doneReason())
                    || r.outcome().answer() == null || r.outcome().sources().isEmpty()) {
                continue;
            }
            try {
                String verdict = chatClient.complete(judgeModel,
                        """
                                你是校园规则答案评审员。只依据【参考资料】与【人工金标准】评分。
                                2=所有必要事实正确完整、无禁止事实、身份和学年适用且引用支撑结论；
                                1=部分正确但缺失必要事实或引用不足；0=事实错误、适用范围错误或无依据。
                                只输出一个数字（0/1/2），不要输出其他内容。""",
                        judgePrompt(r), 0.0, 1.0, 60_000);
                Integer score = parseVerdict(verdict);
                if (score != null) {
                    scores.add(score / 2.0);
                    if (score == 2) {
                        correctScores.add(1.0);
                    } else {
                        correctScores.add(0.0);
                    }
                    backfillJudge(runId, r, score, verdict);
                } else {
                    judgeErrors.add(r.sample().queryId());
                }
            } catch (Exception e) {
                judgeErrors.add(r.sample().queryId() + "(" + e.getMessage() + ")");
            }
        }
        metrics.put("答案正确率(judge归一化均值)", EvalSupport.mean(scores));
        metrics.put("完全正确率(judge=2)", EvalSupport.mean(correctScores));
        metrics.put("judge样本数", scores.size());
        if (!judgeErrors.isEmpty()) {
            System.out.println("  judge 解析失败样本: " + judgeErrors);
        }
        System.out.printf("  judge 完成：样本 %d 条（模型 %s）；判为\"对\"的样本请人工抽检 ≥10 条复核一致率%n",
                scores.size(), judgeModel);
    }

    private String judgePrompt(Recorded r) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (Source s : r.outcome().sources()) {
            sb.append("[").append(s.index()).append("] ").append(s.noteTitle()).append(" ")
                    .append(s.rule()).append("\n").append(s.content().strip()).append("\n\n");
        }
        sb.append("【问题】\n").append(r.sample().query())
                .append("\n【人工金标准·必要事实】\n").append(r.sample().requiredFacts())
                .append("\n【人工金标准·禁止事实】\n").append(r.sample().forbiddenFacts())
                .append("\n【预期适用对象/学年】\n")
                .append(r.sample().expectedAudience()).append(" / ").append(r.sample().expectedAcademicYear())
                .append("\n\n【答案】\n").append(r.outcome().answer());
        return sb.toString();
    }

    private Integer parseVerdict(String verdict) {
        String text = verdict == null ? "" : verdict.strip();
        return text.matches("[012]") ? Integer.parseInt(text) : null;
    }

    private void backfillJudge(long runId, Recorded r, int score, String verdict) {
        try {
            db.sql("""
                            UPDATE eval_record
                            SET judge = CAST(:judge AS jsonb)
                            WHERE query_id = :queryId AND run_id = :runId
                            """)
                    .param("judge", mapper.writeValueAsString(Map.of("score", score, "verdict", verdict.strip())))
                    .param("queryId", r.sample().queryId())
                    .param("runId", runId)
                    .update();
        } catch (Exception ignore) {
            // 回填失败只影响复用，不影响本次指标
        }
    }

    private void writeMetrics(long runId, Map<String, Object> metrics) {
        try {
            db.sql("UPDATE eval_run SET metrics = CAST(:metrics AS jsonb) WHERE id = :id")
                    .param("metrics", mapper.writeValueAsString(metrics))
                    .param("id", runId)
                    .update();
        } catch (Exception e) {
            throw new IllegalStateException("eval_run 指标回填失败: " + e.getMessage(), e);
        }
    }

    // ==================== 报告（含与 baseline 的 diff） ====================

    private void report(long runId, String tag, List<Recorded> recorded, Map<String, Object> metrics) throws Exception {
        Map<String, Object> baseline = loadBaselineMetrics(runId);
        StringBuilder md = new StringBuilder();
        md.append("# 生成层评测报告：").append(tag).append("\n\n");
        md.append(String.format("- 样本：%d 条（库内 %d / 库外 %d）%n",
                recorded.size(),
                recorded.stream().filter(r -> r.sample().requiresRag()).count(),
                recorded.stream().filter(r -> r.sample().strictRefusal()).count()));
        md.append(String.format("- 适用范围近邻：%d 条；只展示结果供人工复核，不计入库外过召回。%n",
                recorded.stream().filter(r -> r.sample().scopeWarning()).count()));
        md.append(String.format("- 配置快照：%s%n", mapper.writeValueAsString(configSnapshot())));
        md.append("\n| 指标 | before(baseline) | after(本次) | Δ |\n|---|---:|---:|---:|\n");
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            Object beforeObj = baseline == null ? null : baseline.get(e.getKey());
            md.append(String.format("| %s | %s | %s | %s |%n",
                    e.getKey(), fmt(e.getKey(), beforeObj), fmt(e.getKey(), e.getValue()),
                    delta(e.getKey(), beforeObj, e.getValue())));
        }

        List<String> failures = new ArrayList<>();
        for (Recorded r : recorded.stream().filter(x -> x.sample().requiresRag()).toList()) {
            Set<String> must = new LinkedHashSet<>(r.sample().expectedChunks());
            if (!must.isEmpty() && EvalSupport.hit(EvalSupport.refs(r.outcome().sources()), must, 5) < 1.0) {
                failures.add(String.format("- [%s] %s：期望 %s，实际 %s",
                        r.sample().queryId(), r.sample().query(), r.sample().expectedChunks(),
                        EvalSupport.refs(r.outcome().sources())));
            }
        }
        for (Recorded r : recorded.stream().filter(x -> x.sample().strictRefusal()).toList()) {
            if (!"no_sources".equals(r.outcome().doneReason())) {
                failures.add(String.format("- [%s] %s：库外问题未被拒答（done.reason=%s）",
                        r.sample().queryId(), r.sample().query(), r.outcome().doneReason()));
            }
        }
        if (!failures.isEmpty()) {
            md.append("\n## 失败明细\n\n").append(String.join("\n", failures)).append('\n');
        }
        md.append("\n## 适用范围近邻（人工复核）\n\n");
        for (Recorded r : recorded.stream().filter(x -> x.sample().scopeWarning()).toList()) {
            md.append("- [").append(r.sample().queryId()).append("] ")
                    .append(r.sample().query()).append("：reason=")
                    .append(r.outcome().doneReason()).append("，sources=")
                    .append(EvalSupport.refs(r.outcome().sources()))
                    .append("，answer=")
                    .append(r.outcome().answer() == null ? "" : r.outcome().answer().replaceAll("\\s+", " "))
                    .append('\n');
        }

        System.out.println();
        System.out.println("================= 生成层评测报告（markdown） =================");
        System.out.println(md);
        Path dir = Path.of("target", "eval-reports");
        Files.createDirectories(dir);
        Path file = dir.resolve(tag + ".md");
        Files.writeString(file, md.toString());
        System.out.println("报告已写入 " + file.toAbsolutePath());
        if (baseline != null) {
            System.out.println("（diff 基线来自相同语料、评测集及实际题数的 tag='campus-baseline' 历史 run）");
        } else {
            System.out.println("（未找到相同语料、评测集及实际题数的校园基线，before 列为空。冻结基线：-Deval.tag=campus-baseline）");
        }
    }

    private Map<String, Object> loadBaselineMetrics(long beforeRunId) {
        try {
            String raw = db.sql("SELECT metrics::text FROM eval_run WHERE tag = 'campus-baseline' "
                            + "AND config->>'dataset'=:dataset AND config->>'corpusFingerprint'=:fingerprint "
                            + "AND config->>'datasetFingerprint'=:datasetFingerprint "
                            + "AND config->>'sampleCount'=:sampleCount AND config->>'queryIds'=:queryIds "
                            + "AND metrics IS NOT NULL AND id < :beforeRunId ORDER BY id DESC LIMIT 1")
                    .param("dataset", EVAL_SET)
                    .param("fingerprint", corpusFingerprint())
                    .param("datasetFingerprint", datasetFingerprint())
                    .param("sampleCount", String.valueOf(evaluatedSampleCount))
                    .param("queryIds", evaluatedQueryIds)
                    .param("beforeRunId", beforeRunId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (raw == null) {
                return null;
            }
            return mapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    /** 计数类指标（整数展示），其余均按比率百分比展示 */
    private static final Set<String> COUNT_METRICS = Set.of("零角标答案数", "judge样本数",
            "已完成样本数", "错误样本数", "适用范围待复核样本数");

    private static String fmt(String key, Object value) {
        Double d = toDouble(value);
        if (d == null) {
            return value == null ? "—" : String.valueOf(value);
        }
        if (COUNT_METRICS.contains(key)) {
            return String.valueOf(Math.round(d));
        }
        if (key.endsWith("_ms")) return String.format("%.0f ms", d);
        return String.format("%.1f%%", d * 100);
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String delta(String key, Object beforeObj, Object afterObj) {
        Double before = toDouble(beforeObj);
        Double after = toDouble(afterObj);
        if (before == null || after == null) {
            return "—";
        }
        if (COUNT_METRICS.contains(key)) {
            return String.format("%+.0f", after - before);
        }
        if (key.endsWith("_ms")) return String.format("%+.0f ms", after - before);
        return String.format("%+.1fpp", (after - before) * 100);
    }

    private Map<String, Object> configSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", EVAL_SET);
        m.put("datasetFingerprint", datasetFingerprint());
        m.put("sampleCount", evaluatedSampleCount);
        m.put("queryIds", evaluatedQueryIds);
        m.put("corpusFingerprint", corpusFingerprint());
        m.put("embeddingModel", props.getEmbeddingModel());
        m.put("chatModel", props.getChatModel());
        m.put("embeddingApiBaseUrl", props.getApiBaseUrl());
        m.put("chatApiBaseUrl", props.getChatApiBaseUrl());
        m.put("topK", props.getTopK());
        m.put("minSimilarity", props.getMinSimilarity());
        m.put("candidateLimit", props.getRetrieval().getCandidateLimit());
        m.put("vectorChannel", props.getChannels().getVector().isEnabled());
        m.put("keywordChannel", props.getChannels().getKeyword().isEnabled());
        m.put("fusion", props.getFusion().getStrategy());
        m.put("rrfK", props.getFusion().getRrfK());
        m.put("channelWeights", props.getFusion().getChannelWeights());
        m.put("gateMode", props.getEvidence().getMode());
        m.put("minGateScore", props.getEvidence().getMinGateScore());
        m.put("chunkStrategy", props.getChunk().getStrategy());
        m.put("chunkMaxChars", props.getChunk().getMaxChars());
        m.put("rewriteEnabled", props.getRewrite().isEnabled());
        return m;
    }

    private String corpusFingerprint() {
        return db.sql("""
                        SELECT COALESCE(string_agg(concat_ws(':', id, content_revision,
                            COALESCE(source_url,''), COALESCE(version,''), COALESCE(issuer,''),
                            COALESCE(audience,''), COALESCE(campus,''), COALESCE(academic_year,''),
                            COALESCE(effective_from::text,''), COALESCE(effective_to::text,'')),
                            '|' ORDER BY id), 'EMPTY')
                        FROM note WHERE status='active' AND index_ready=TRUE
                        """)
                .query(String.class).single();
    }

    private String datasetFingerprint() {
        try (var input = GenerationEvalTest.class.getResourceAsStream(EVAL_SET)) {
            if (input == null) throw new IllegalStateException("找不到评测集：" + EVAL_SET);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算评测集指纹：" + EVAL_SET, e);
        }
    }

    private record Recorded(EvalSample sample, RagService.ChatOutcome outcome) {
    }
}
