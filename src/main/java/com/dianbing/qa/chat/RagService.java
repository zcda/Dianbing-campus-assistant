package com.dianbing.qa.chat;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.campus.CampusQuestionRouter;
import com.dianbing.qa.conversation.ChatMessage;
import com.dianbing.qa.conversation.ConversationService;
import com.dianbing.infrastructure.llm.ChatClient;
import com.dianbing.infrastructure.llm.EmbeddingClient;
import com.dianbing.qa.rewrite.QueryRewriter;
import com.dianbing.qa.rewrite.TermMapper;
import com.dianbing.qa.retrieval.RetrievalEngine;
import com.dianbing.qa.retrieval.RetrievalOutcome;
import com.dianbing.qa.retrieval.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * RAG 问答编排（改造 v1，设计文档 §2.1 链路）：
 *
 * <pre>
 * 问题 + 历史 → ① 问句归一（词表映射，纯规则零 token）
 *   → ② 改写 + 多问句拆分（LLM，与向量化并行；失败 → 规则切分兜底）
 *   → ③ 子问题并行召回（向量 + 关键词双通道，各自独立扩池）
 *   → ④ 后置处理器链 Dedup → Fusion(RRF) → ScoreFill → EvidenceGate
 *   → ⑤ 闸门判定（不过线整批丢弃 → 直接拒答，不调 LLM）
 *   → ⑥ Prompt 组装（合并去重重编号，编号与 sources 下标严格一致）
 *   → ⑦ LLM 流式生成 → ⑧ 角标覆盖率埋点（只度量不改写）
 *   → ⑨ SSE：meta → sources → delta* → done(reason, traceId, llmCalls) / error
 * </pre>
 *
 * <p>SSE 协议只增不改：旧前端不监听 meta、忽略 done 里的新字段，功能不受影响。
 * 评测（D7 录制）走同一条核心链路（EventSink 抽象），不依赖 HTTP。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 历史消息进入 Prompt / 改写器时的截断长度，避免上下文膨胀 */
    private static final int HISTORY_CONTENT_LIMIT = 300;

    private final EmbeddingClient embeddingClient;
    private final ChatClient chatClient;
    private final RetrievalEngine retrievalEngine;
    private final ConversationService conversations;
    private final QueryRewriter queryRewriter;
    private final TermMapper termMapper;
    private final RagProperties props;
    private final Executor executor;
    private final CampusQuestionRouter campusQuestions;

    public RagService(EmbeddingClient embeddingClient, ChatClient chatClient,
                      RetrievalEngine retrievalEngine, ConversationService conversations,
                      QueryRewriter queryRewriter, TermMapper termMapper,
                      RagProperties props, @Qualifier("chatExecutor") Executor executor,
                      CampusQuestionRouter campusQuestions) {
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.retrievalEngine = retrievalEngine;
        this.conversations = conversations;
        this.queryRewriter = queryRewriter;
        this.termMapper = termMapper;
        this.props = props;
        this.executor = executor;
        this.campusQuestions = campusQuestions;
    }

    /** 事件出口抽象：SSE 与评测录制共用同一条问答链路 */
    public interface EventSink {
        void emit(String event, Object data);
    }

    /** 一次问答的完整结果（D7 评测录制用；citations 仅在生成路径非空） */
    public record ChatOutcome(String traceId, String doneReason, String answer,
                              List<Source> sources, Map<String, Object> meta,
                              int llmCalls, CitationCheck.Result citations,
                              Map<String, Object> timings) {
    }

    /** ①②③ 的产物（RagService 与 /api/debug/retrieval 共用同一套准备逻辑） */
    public record PreparedQuery(String question, String normalized, QueryRewriter.Result rewrite,
                                List<String> subQueries, List<float[]> vectors,
                                long rewriteMs, long embedMs) {
    }

    // ==================== SSE 入口（协议只增不改） ====================

    public void chat(long conversationId, String question, SseEmitter emitter) {
        try {
            chat(conversationId, question, sseSink(emitter));
        } catch (Exception e) {
            log.warn("RAG 问答失败: {}", e.toString());
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", friendlyMessage(e))));
            } catch (Exception ignore) {
                // 客户端可能已断开
            }
        } finally {
            safeComplete(emitter);
        }
    }

    // ==================== 核心链路（SSE 与评测共用） ====================

    public ChatOutcome chat(long conversationId, String question, EventSink sink) throws Exception {
        long t0 = System.currentTimeMillis();
        String traceId = shortTraceId();
        Timings t = new Timings();

        // 取历史要在提问落库之前，否则本次提问会被算进"历史"
        List<ChatMessage> history = conversations.rewriteHistory(conversationId);
        conversations.appendUserMessage(conversationId, question);

        // 纯个人数据问题由校园服务回答；混合问题继续进入 RAG，并在生成阶段联合工具结果与规则证据。
        var campusAnswer = campusQuestions.answer(question);
        if (campusAnswer.isPresent() && !campusAnswer.get().needsRuleEvidence()) {
            var answer = campusAnswer.get();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("traceId", traceId);
            meta.put("route", answer.route());
            meta.put("dataSource", "fictional-mock");
            meta.put("llmCalls", answer.llmCalls());
            meta.put("toolCalls", answer.toolCalls());
            sink.emit("meta", meta);
            sink.emit("delta", Map.of("text", answer.text()));
            persistAnswer(conversationId, answer.text(), List.of());
            t.totalMs = System.currentTimeMillis() - t0;
            String campusReason = answer.route().equals("campus_agent") ? "campus_agent" : "campus_mock";
            sink.emit("done", done(campusReason, traceId, answer.llmCalls(), null, t));
            return new ChatOutcome(traceId, campusReason, answer.text(), List.of(),
                    meta, answer.llmCalls(), null, t.snapshot());
        }

        boolean mixedCampusRule = campusAnswer.isPresent() && campusAnswer.get().needsRuleEvidence();
        String retrievalQuestion = mixedCampusRule ? campusAnswer.get().ruleQuery() : question;
        List<ChatMessage> promptHistory = mixedCampusRule ? List.of() : publicRuleHistory(history);

        // ① 归一 → ② 改写拆分（与主问句向量化并行）→ ③ 子问题向量
        PreparedQuery prepared = prepare(retrievalQuestion, promptHistory);
        t.rewriteMs = prepared.rewriteMs();
        t.embedMs = prepared.embedMs();

        // ④⑤ 双通道并行召回 + 后置链 + 批级闸门（子问题并行，各自独立扩池）
        RetrievalOutcome outcome = retrievalEngine.retrieve(prepared.subQueries(), prepared.vectors(), retrievalQuestion);
        t.retrieveMs = outcome.elapsedMs();
        t.gateMs = outcome.gateMs();

        // meta 首个事件（只增不改：旧前端忽略未知事件名）
        Map<String, Object> meta = meta(traceId, prepared, outcome, t);
        if (mixedCampusRule) {
            meta.put("route", "campus_rule_mix_mock");
            meta.put("dataSource", "fictional-mock");
        }
        sink.emit("meta", meta);

        // ⑦ 护栏/闸门拒答：由检索侧决定是否调 LLM，不依赖模型自觉（D8 核心原则）
        if (outcome.sources().isEmpty()) {
            String notFound = props.getNotFoundAnswer();
            var requestedScope = com.dianbing.qa.retrieval.RuleScope.from(retrievalQuestion);
            if (requestedScope.hasMetadataFilter()) {
                notFound += " 当前有效资料中没有与问题明确指定的身份、年级或校区全部匹配的规则，请核对适用范围或补充对应版本。";
            }
            String response = mixedCampusRule
                    ? campusAnswer.get().text() + "\n\n**学校规则依据**：" + notFound
                    : notFound;
            sink.emit("delta", Map.of("text", response));
            persistAnswer(conversationId, response, List.of());
            t.totalMs = System.currentTimeMillis() - t0;
            String reason = mixedCampusRule ? "campus_rule_no_sources" : "no_sources";
            sink.emit("done", done(reason, traceId, 0, null, t));
            summaryLog(traceId, conversationId, question, prepared, outcome, 0, t, null);
            return new ChatOutcome(traceId, reason, response, List.of(), meta, 0, null, t.snapshot());
        }

        sink.emit("sources", outcome.sources());

        // ⑧⑨ LLM 流式生成 + 首字计时 + 生成后角标埋点（F3 的解药：漏打角标可度量）
        StringBuilder answer = new StringBuilder();
        long[] firstTokenAt = {-1};
        CitationCheck.Result[] citations = {null};
        String prompt = mixedCampusRule
                ? buildMixedUserPrompt(question, campusAnswer.get().text(), outcome.sources())
                : buildUserPrompt(question, outcome.sources(), promptHistory);
        String instructions = mixedCampusRule
                ? systemPrompt() + """

                        本次是校园助手的联合查询演示。【个人学业数据】来自校园工具，视为当前演示用户的可信教务数据；【参考资料】来自校规 RAG。
                        必须把两者结合起来回答原问题：资料充分时明确判断是否满足，并按总学分和类别说明已修、要求及缺口；资料不足时准确指出缺少哪项规则，仍要展示能够确定的个人数据。
                        个人学业数据不能作为学校规则依据；学校规则结论必须引用参考资料。答案末尾简短注明结论基于 Mock 数据，仅用于功能演示。
                        """
                : systemPrompt();
        chatClient.stream(instructions, prompt,
                new ChatClient.Listener() {
                    @Override
                    public void onDelta(String text) {
                        if (firstTokenAt[0] < 0) {
                            firstTokenAt[0] = System.currentTimeMillis();
                        }
                        answer.append(text);
                        sink.emit("delta", Map.of("text", text));
                    }

                    @Override
                    public void onDone() {
                        t.llmFirstTokenMs = firstTokenAt[0] < 0 ? 0 : firstTokenAt[0] - t0;
                        t.totalMs = System.currentTimeMillis() - t0;
                        citations[0] = CitationCheck.check(answer.toString(), outcome.sources().size());
                        var numericAudit = NumericCitationCheck.check(answer.toString(), outcome.sources());
                        persistAnswer(conversationId, answer.toString(), outcome.sources());
                        Map<String, Object> completion = done(mixedCampusRule ? "campus_rule_mix" : "ok",
                                traceId, 1, citations[0], t);
                        if (numericAudit.total() > 0) {
                            completion.put("numericCreditCitations", Map.of(
                                    "total", numericAudit.total(),
                                    "supported", numericAudit.supported(),
                                    "unsupported", numericAudit.unsupported()));
                        }
                        sink.emit("done", completion);
                        summaryLog(traceId, conversationId, question, prepared, outcome, 1, t, citations[0]);
                    }
                });
        return new ChatOutcome(traceId, mixedCampusRule ? "campus_rule_mix" : "ok",
                answer.toString(), outcome.sources(), meta, 1, citations[0], t.snapshot());
    }

    /** ①②③：归一 → 改写（与主问句向量化并行发起，避免串行叠加首字延迟）→ 子问题向量 */
    public PreparedQuery prepare(String question, List<ChatMessage> history) {
        // /api/debug/retrieval 也调用此入口，不能绕过个人数据历史隔离。
        List<ChatMessage> safeHistory = publicRuleHistory(history);
        // ① 问句归一（词表映射，纯规则零 token；默认关闭时原样返回）
        String normalized = termMapper.normalize(question).strip();

        long rewriteStart = System.currentTimeMillis();
        // 改写（LLM）与主问句向量化（Embedding）并行发起
        CompletableFuture<float[]> embedFuture = CompletableFuture.supplyAsync(
                () -> embeddingClient.embed(normalized), executor);
        QueryRewriter.Result rewrite;
        if (props.getRewrite().isEnabled()) {
            rewrite = queryRewriter.rewrite(normalized, historyLines(safeHistory));
        } else {
            rewrite = new QueryRewriter.Result(normalized, List.of(normalized), false);
        }
        long rewriteMs = System.currentTimeMillis() - rewriteStart;

        long embedStart = System.currentTimeMillis();
        List<String> subQueries = rewrite.subQuestions();
        List<float[]> vectors;
        if (subQueries.size() == 1 && subQueries.get(0).contentEquals(normalized)) {
            // 改写没有改变查询文本：复用与改写并行的那份向量，省一次 Embedding 往返
            vectors = List.of(joinEmbed(embedFuture));
        } else {
            // 拆分出子问题 / 改写改变了文本：一次 embedBatch 批量向量化（子问题 ≤ max-sub-questions）
            embedFuture.cancel(true);
            vectors = embeddingClient.embedBatch(subQueries);
        }
        long embedMs = System.currentTimeMillis() - embedStart;
        return new PreparedQuery(question, normalized, rewrite, subQueries, vectors, rewriteMs, embedMs);
    }

    private float[] joinEmbed(CompletableFuture<float[]> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    // ==================== Prompt 组装（D8：拒答口径单一事实来源） ====================

    private String systemPrompt() {
        return """
                你是“电兵助手”高校校园智能助手。回答校园规则问题时，只依据【参考资料】，不得凭常识补充校规。
                每个规则性结论后紧跟支持它的 [1]、[2] 引用；不同数字若来自不同片段，要分别标对应编号，不要只在文档标题后放一个引用。
                先直接回答问题问到的每一项；若问多个数值或条件，逐项核对原文并逐项写出，不得只回答其中一项。
                只在与问题相关且资料确有依据时补充适用对象、条件、流程或例外；不要套用空的模板标题。
                严格核对每份资料的适用身份、校区、学年、生效日期和版本，不得混用不同适用范围。
                当前是公开规则库：身份、校区和学年是资料适用范围，不是用户访问权限。问题未指定时可以回答，但必须在答案中说明资料的适用范围；如果存在多个范围，列出差异。
                当资料互相矛盾或无法判断哪份为准时，列出冲突及各自来源，不要自行裁定。
                如果资料与问题无关或不足以回答，请回答“%s”。对于部分可回答的问题，只回答有依据的部分，并明确未找到的部分。
                【历史对话】只用于理解指代，绝不能作为答案依据。使用简体中文。"""
                .formatted(props.getNotFoundAnswer());
    }

    private String buildUserPrompt(String question, List<Source> sources, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        if (!history.isEmpty()) {
            sb.append("【历史对话】（仅用于理解指代，不能作为答案依据）\n");
            for (ChatMessage message : history) {
                sb.append("user".equals(message.role()) ? "用户：" : "助手：")
                        .append(abbreviate(message.content()))
                        .append("\n");
            }
            sb.append("\n");
        }
        sb.append("【参考资料】\n");
        for (Source s : sources) {
            sb.append("[").append(s.index()).append("] 《").append(s.noteTitle()).append("》");
            if (s.rule() != null) {
                var r = s.rule();
                sb.append("；发布部门：").append(r.issuer())
                        .append("；适用对象：").append(r.audience())
                        .append("；校区：").append(r.campus())
                        .append("；学年：").append(r.academicYear())
                        .append("；生效：").append(r.effectiveFrom())
                        .append("；失效：").append(r.effectiveTo())
                        .append("；版本：").append(r.version());
                if (r.articleNo() != null) sb.append("；条款：").append(r.articleNo());
            }
            sb.append("\n").append(s.content().strip()).append("\n\n");
        }
        sb.append("【问题】\n").append(question);
        return sb.toString();
    }

    private String buildMixedUserPrompt(String question, String personalData, List<Source> sources) {
        return "【个人学业数据】\n" + personalData.strip() + "\n\n"
                + buildUserPrompt(question, sources, List.of());
    }

    /** 改写器用的历史行（用户提问全保留、助手回复只留最近 2 条 —— 过滤在 ConversationService 完成） */
    private List<String> historyLines(List<ChatMessage> history) {
        return history.stream()
                .map(m -> ("user".equals(m.role()) ? "用户：" : "助手：") + abbreviate(m.content()))
                .toList();
    }

    /** 旧轮次的校园个人数据不进入公开校规上下文；混合问题会重新调用工具并显式传入当前结果。 */
    private List<ChatMessage> publicRuleHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<ChatMessage> safe = new ArrayList<>();
        boolean precedingCampusQuestion = false;
        for (ChatMessage message : history) {
            if ("user".equals(message.role())) {
                precedingCampusQuestion = campusQuestions.answer(message.content()).isPresent();
                if (!precedingCampusQuestion) safe.add(message);
            } else if ("assistant".equals(message.role())) {
                // 兼容已落库、没有结构化路由标签的旧对话，含 Mock 标识的孤立回复也排除。
                if (!precedingCampusQuestion && !isMockReply(message.content())) safe.add(message);
                precedingCampusQuestion = false;
            }
        }
        return safe;
    }

    private boolean isMockReply(String content) {
        return content.contains("**Mock ") || content.contains("**虚构学生的模拟学业数据**")
                || content.contains("以上均为虚构数据") || content.contains("以上为虚构安排");
    }

    private String abbreviate(String content) {
        String text = content.strip().replaceAll("\\s+", " ");
        return text.length() > HISTORY_CONTENT_LIMIT ? text.substring(0, HISTORY_CONTENT_LIMIT) + "…" : text;
    }

    // ==================== meta / done / 汇总日志（D11） ====================

    private Map<String, Object> meta(String traceId, PreparedQuery prepared,
                                     RetrievalOutcome outcome, Timings t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", traceId);
        m.put("subQuestions", prepared.subQueries());
        m.put("rewriteByLlm", prepared.rewrite().byLlm());
        m.put("channels", outcome.channelCounts());
        m.put("candidateCount", outcome.candidateCount());
        m.put("gateScore", outcome.gateScore());
        m.put("gateDecision", outcome.gateDecision());
        m.put("timings", t.snapshot());
        return m;
    }

    private Map<String, Object> done(String reason, String traceId, int llmCalls,
                                     CitationCheck.Result citations, Timings t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        m.put("traceId", traceId);
        m.put("llmCalls", llmCalls);
        if (citations != null) {
            m.put("citations", Map.of("total", citations.total(), "valid", citations.valid()));
        }
        m.put("timings", t.snapshot());
        return m;
    }

    /** 一次问答一条汇总日志（D11-3）：这条为什么答错/拒答，在日志里一眼能看出来 */
    private void summaryLog(String traceId, long conversationId, String question,
                            PreparedQuery prepared, RetrievalOutcome outcome,
                            int llmCalls, Timings t, CitationCheck.Result citations) {
        log.info("QA汇总 traceId={} conv={} q=«{}» subs={} byLlm={} channels={} gate(score={},decision={}) candidates={} sources={} llmCalls={} timings(rewrite={}ms embed={}ms retrieve={}ms gate={}ms ttft={}ms total={}ms) citations={}",
                traceId, conversationId, abbreviateQ(question), prepared.subQueries().size(),
                prepared.rewrite().byLlm(), outcome.channelCounts(),
                outcome.gateScore() == null ? "-" : String.format("%.3f", outcome.gateScore()),
                outcome.gateDecision(), outcome.candidateCount(), outcome.sources().size(), llmCalls,
                t.rewriteMs, t.embedMs, t.retrieveMs, t.gateMs, t.llmFirstTokenMs, t.totalMs,
                citations == null ? "-" : citations.valid() + "/" + citations.total());
    }

    private String abbreviateQ(String question) {
        String text = question.strip().replaceAll("\\s+", " ");
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }

    // ==================== 基础设施 ====================

    /** 落库失败不影响已经流式返回给用户的答案，仅记日志 */
    private void persistAnswer(long conversationId, String answer, List<Source> sources) {
        try {
            conversations.appendAssistantMessage(conversationId, answer, sources);
        } catch (Exception e) {
            log.warn("会话[{}]回答落库失败: {}", conversationId, e.getMessage());
        }
    }

    private EventSink sseSink(SseEmitter emitter) {
        return (event, data) -> {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 已完成/已断开
        }
    }

    private String shortTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 各步耗时（ms，-1 表示尚未发生；llmFirstTokenMs=0 表示未产生任何 token） */
    static final class Timings {
        long rewriteMs = -1;
        long embedMs = -1;
        long retrieveMs = -1;
        long gateMs = -1;
        long llmFirstTokenMs = -1;
        long totalMs = -1;

        Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rewriteMs", rewriteMs);
            m.put("embedMs", embedMs);
            m.put("retrieveMs", retrieveMs);
            m.put("gateMs", gateMs);
            m.put("llmFirstTokenMs", llmFirstTokenMs);
            m.put("totalMs", totalMs);
            return m;
        }
    }

    private String friendlyMessage(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        if (msg.contains("API Key")) {
            return "尚未配置 LLM API Key（环境变量 LLM_API_KEY），无法进行语义检索与问答。笔记功能不受影响。";
        }
        if (msg.contains("401")) {
            return "API Key 无效或未授权（HTTP 401），请检查 LLM_API_KEY。";
        }
        if (msg.contains("429")) {
            return "模型服务限流（HTTP 429），请稍后重试。";
        }
        String lower = msg.toLowerCase();
        if (lower.contains("not found, try pulling") || lower.contains("model not found")
                || lower.contains("model \"") && lower.contains("not found")) {
            return "模型未拉取：请先执行 ollama pull 拉取所配模型（chat=" + props.getChatModel()
                    + ", embedding=" + props.getEmbeddingModel() + "）";
        }
        if (lower.contains("connection refused") || lower.contains("connectexception")
                || lower.contains("connection reset")) {
            return "无法连接模型服务，请确认 Ollama 已启动（默认 http://localhost:11434，可用 ollama serve 启动）。";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "调用模型服务超时，请稍后重试。";
        }
        return "问答失败：" + (msg.length() > 200 ? msg.substring(0, 200) + "…" : msg);
    }
}
