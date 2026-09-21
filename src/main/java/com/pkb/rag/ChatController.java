package com.pkb.rag;

import com.pkb.config.RagProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executor;

@RestController
public class ChatController {

    public record ChatRequest(String question) {
    }

    private final RagService ragService;
    private final RagProperties props;
    private final Executor executor;

    public ChatController(RagService ragService, RagProperties props,
                          @Qualifier("chatExecutor") Executor executor) {
        this.ragService = ragService;
        this.props = props;
        this.executor = executor;
    }

    @PostMapping("/api/chat")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        SseEmitter emitter = new SseEmitter(300_000L);
        executor.execute(() -> ragService.chat(question, emitter));
        return emitter;
    }

    /** 前端据此提示"未配置 Key"状态 */
    @GetMapping("/api/config")
    public Map<String, Object> config() {
        boolean hasApiKey = props.getApiKey() != null && !props.getApiKey().isBlank();
        return Map.of("hasApiKey", hasApiKey);
    }
}
