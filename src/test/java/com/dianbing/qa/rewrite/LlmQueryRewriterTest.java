package com.dianbing.qa.rewrite;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.infrastructure.llm.ChatClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmQueryRewriterTest {
    @Test
    void c119UsesDeterministicSplitWithoutLlmLatency() {
        ChatClient client = mock(ChatClient.class);
        var rewriter = new LlmQueryRewriter(client, new RuleBasedSplitter(), new RagProperties());
        var result = rewriter.rewrite("软件工程学硕学位课和课程总学分分别至少多少？", List.of());
        assertEquals(List.of("软件工程学硕学位课至少多少？",
                "软件工程学硕课程总学分至少多少？"), result.subQuestions());
        assertFalse(result.byLlm());
        verifyNoInteractions(client);
    }
}
