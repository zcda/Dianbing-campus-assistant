package com.pkb.rag;

import com.pkb.conversation.ChatMessage;
import com.pkb.conversation.ConversationService;
import com.pkb.search.RetrievalEngine;
import com.pkb.search.RetrievalOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试接口（设计文档 D11，最便宜的加分项）：
 * GET /api/debug/retrieval?q=...&explain=true[&conversationId=]
 *
 * <p>返回每个子问题 → 每个通道的原始命中（分数与名次）→ RRF 分 → gateScore/gateDecision →
 * 最终送入 Prompt 的片段（含重编号 [n]）。把 D1/D2/D3/D4 一次性变成"看得见的东西"：
 * "这条为什么答错"能在响应里一眼看出来（如 E01 哪个通道没命中、闸门判了什么）。
 */
@RestController
public class DebugController {

    private final RagService ragService;
    private final RetrievalEngine retrievalEngine;
    private final ConversationService conversations;

    public DebugController(RagService ragService, RetrievalEngine retrievalEngine,
                           ConversationService conversations) {
        this.ragService = ragService;
        this.retrievalEngine = retrievalEngine;
        this.conversations = conversations;
    }

    @GetMapping("/api/debug/retrieval")
    public Map<String, Object> retrieval(@RequestParam String q,
                                         @RequestParam(defaultValue = "false") boolean explain,
                                         @RequestParam(required = false) Long conversationId) {
        String question = q == null ? "" : q.trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数 q 不能为空");
        }
        List<ChatMessage> history = conversationId == null
                ? List.of()
                : conversations.rewriteHistory(conversationId);

        RagService.PreparedQuery prepared = ragService.prepare(question, history);
        RetrievalOutcome outcome = retrievalEngine.retrieve(prepared.subQueries(), prepared.vectors());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", question);
        m.put("normalized", prepared.normalized());
        m.put("rewrite", Map.of(
                "rewritten", prepared.rewrite().rewritten(),
                "subQuestions", prepared.rewrite().subQuestions(),
                "byLlm", prepared.rewrite().byLlm()));
        m.put("timings", Map.of(
                "rewriteMs", prepared.rewriteMs(),
                "embedMs", prepared.embedMs(),
                "retrieveMs", outcome.elapsedMs(),
                "gateMs", outcome.gateMs()));
        m.put("channels", outcome.channelCounts());
        m.put("candidateCount", outcome.candidateCount());
        m.put("gateScore", outcome.gateScore());
        m.put("gateDecision", outcome.gateDecision());
        m.put("sources", outcome.sources());
        if (explain) {
            m.put("explain", outcome.subQueryTrace());
        }
        return m;
    }
}
