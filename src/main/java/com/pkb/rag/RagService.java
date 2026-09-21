package com.pkb.rag;

import com.pkb.config.RagProperties;
import com.pkb.llm.ChatClient;
import com.pkb.llm.EmbeddingClient;
import com.pkb.search.Source;
import com.pkb.search.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * RAG 问答编排：问题向量化 → Top-K 检索 → 组装 Prompt → LLM 流式生成。
 * SSE 事件协议：sources（引用数组）→ delta（增量文本，多次）→ done / error。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String SYSTEM_PROMPT = """
            你是"个人知识库"问答助手。请严格遵守以下规则：
            1. 只能依据用户提供的【参考资料】回答【问题】，禁止使用资料之外的知识；
            2. 答案中的每个论断都必须用 [1]、[2] 这样的角标标注来源编号；
            3. 如果参考资料与问题无关或不足以回答，必须直接回答"知识库中未找到相关内容"，禁止编造；
            4. 使用简体中文回答，条理清晰，可适度使用列表与代码块。""";

    private final EmbeddingClient embeddingClient;
    private final ChatClient chatClient;
    private final VectorSearchService searchService;
    private final RagProperties props;

    public RagService(EmbeddingClient embeddingClient, ChatClient chatClient,
                      VectorSearchService searchService, RagProperties props) {
        this.embeddingClient = embeddingClient;
        this.chatClient = chatClient;
        this.searchService = searchService;
        this.props = props;
    }

    public void chat(String question, SseEmitter emitter) {
        try {
            float[] queryVector = embeddingClient.embed(question);
            List<Source> sources = searchService.search(queryVector);
            send(emitter, "sources", sources);

            if (sources.isEmpty()) {
                // 相似度阈值护栏：没有足够相关的片段时不调用 LLM，直接兜底回答
                send(emitter, "delta", Map.of("text", "知识库中未找到与该问题相关的内容。可以先在左侧补充相关笔记，或换个问法试试。"));
                send(emitter, "done", Map.of("reason", "no_sources"));
                safeComplete(emitter);
                return;
            }

            chatClient.stream(SYSTEM_PROMPT, buildUserPrompt(question, sources), new ChatClient.Listener() {
                @Override
                public void onDelta(String text) {
                    send(emitter, "delta", Map.of("text", text));
                }

                @Override
                public void onDone() {
                    send(emitter, "done", Map.of("reason", "ok"));
                    safeComplete(emitter);
                }
            });
        } catch (Exception e) {
            log.warn("RAG 问答失败: {}", e.toString());
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", friendlyMessage(e))));
            } catch (Exception ignore) {
                // 客户端可能已断开
            }
            safeComplete(emitter);
        }
    }

    private String buildUserPrompt(String question, List<Source> sources) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (Source s : sources) {
            sb.append("[").append(s.index()).append("] 《").append(s.noteTitle()).append("》\n")
                    .append(s.content().strip()).append("\n\n");
        }
        sb.append("【问题】\n").append(question);
        return sb.toString();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 已完成/已断开
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
