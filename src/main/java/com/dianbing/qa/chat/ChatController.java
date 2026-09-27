package com.dianbing.qa.chat;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.qa.conversation.ConversationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
public class ChatController {

    public record ChatRequest(Long conversationId, String question) {
    }

    private final RagService ragService;
    private final ConversationService conversationService;
    private final RagProperties props;
    private final Executor executor;

    public ChatController(RagService ragService, ConversationService conversationService,
                          RagProperties props, @Qualifier("chatExecutor") Executor executor) {
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.props = props;
        this.executor = executor;
    }

    /** 每个会话一次请求：消息落库、检索、流式回答，多个会话可同时进行 */
    @PostMapping("/api/chat")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        if (request.conversationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 conversationId");
        }
        long conversationId = request.conversationId();
        conversationService.get(conversationId);

        SseEmitter emitter = new SseEmitter(300_000L);
        executor.execute(() -> ragService.chat(conversationId, question, emitter));
        return emitter;
    }

    /** 前端据此提示"未配置 Key"状态；改造 v1 增加闸门/融合/分块当前取值（面试演示与调试用，只增不改） */
    @GetMapping("/api/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean hasApiKey = props.getChatApiKey() != null && !props.getChatApiKey().isBlank();
        m.put("hasApiKey", hasApiKey);
        m.put("models", Map.of("chat", props.getChatModel(), "embedding", props.getEmbeddingModel()));
        m.put("topK", props.getTopK());
        m.put("minSimilarity", props.getMinSimilarity());
        m.put("candidateLimit", props.getRetrieval().getCandidateLimit());
        m.put("channels", Map.of(
                "vector", props.getChannels().getVector().isEnabled(),
                "keyword", props.getChannels().getKeyword().isEnabled()));
        m.put("fusion", Map.of(
                "strategy", props.getFusion().getStrategy(),
                "rrfK", props.getFusion().getRrfK(),
                "channelWeights", props.getFusion().getChannelWeights()));
        m.put("evidence", Map.of(
                "mode", props.getEvidence().getMode(),
                "minGateScore", props.getEvidence().getMinGateScore()));
        m.put("chunk", Map.of(
                "strategy", props.getChunk().getStrategy(),
                "maxChars", props.getChunk().getMaxChars(),
                "overlapChars", props.getChunk().getOverlapChars() != null
                        ? props.getChunk().getOverlapChars() : props.getChunk().getMaxChars() / 8,
                "toleranceFactor", props.getChunk().getToleranceFactor()));
        m.put("rewrite", Map.of(
                "enabled", props.getRewrite().isEnabled(),
                "maxSubQuestions", props.getRewrite().getMaxSubQuestions()));
        m.put("index", Map.of("asyncEnabled", props.getIndex().isAsyncEnabled()));
        return m;
    }
}
