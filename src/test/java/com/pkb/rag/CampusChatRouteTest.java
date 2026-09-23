package com.pkb.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pkb.campus.CampusQuestionRouter;
import com.pkb.campus.CampusService;
import com.pkb.campus.MockCampusDataProvider;
import com.pkb.config.RagProperties;
import com.pkb.conversation.ConversationService;
import com.pkb.conversation.ChatMessage;
import com.pkb.llm.ChatClient;
import com.pkb.llm.EmbeddingClient;
import com.pkb.rewrite.QueryRewriter;
import com.pkb.rewrite.TermMapper;
import com.pkb.search.RetrievalEngine;
import com.pkb.search.RetrievalOutcome;
import com.pkb.search.Source;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CampusChatRouteTest {
    @Test
    void personalGradeQuestionSkipsEmbeddingRetrievalAndLlm() throws Exception {
        var embeddings = mock(EmbeddingClient.class);
        var chatModel = mock(ChatClient.class);
        var retrieval = mock(RetrievalEngine.class);
        var conversations = mock(ConversationService.class);
        var rewriter = mock(QueryRewriter.class);
        var terms = mock(TermMapper.class);
        var router = new CampusQuestionRouter(new CampusService(
                new MockCampusDataProvider(new ObjectMapper())));
        var service = new RagService(embeddings, chatModel, retrieval, conversations, rewriter,
                terms, new RagProperties(), Runnable::run, router);
        List<String> events = new ArrayList<>();

        var result = service.chat(1L, "我的成绩是多少？", (event, data) -> events.add(event));

        assertEquals("campus_mock", result.doneReason());
        assertEquals(0, result.llmCalls());
        assertEquals(List.of("meta", "delta", "done"), events);
        verify(conversations).appendUserMessage(1L, "我的成绩是多少？");
        verify(conversations).appendAssistantMessage(eq(1L), contains("虚构数据"), eq(List.of()));
        verifyNoInteractions(embeddings, chatModel, retrieval, rewriter, terms);
    }

    @Test
    void freeClassroomQuestionUsesMockRouteWithoutModels() throws Exception {
        var embeddings = mock(EmbeddingClient.class);
        var chatModel = mock(ChatClient.class);
        var retrieval = mock(RetrievalEngine.class);
        var conversations = mock(ConversationService.class);
        var rewriter = mock(QueryRewriter.class);
        var terms = mock(TermMapper.class);
        var router = new CampusQuestionRouter(new CampusService(
                new MockCampusDataProvider(new ObjectMapper())));
        var service = new RagService(embeddings, chatModel, retrieval, conversations, rewriter,
                terms, new RagProperties(), Runnable::run, router);

        var result = service.chat(1L, "周二第3-4节有哪些空教室？", (event, data) -> {});

        assertEquals("campus_mock", result.doneReason());
        assertEquals("free_classrooms_mock", result.meta().get("route"));
        assertTrue(result.answer().contains("A103"));
        verifyNoInteractions(embeddings, chatModel, retrieval, rewriter, terms);
    }

    @Test
    void mixedQuestionCombinesMockDataAndCitedRulesWithoutSendingGradesToModel() throws Exception {
        var embeddings = mock(EmbeddingClient.class);
        var chatModel = mock(ChatClient.class);
        var retrieval = mock(RetrievalEngine.class);
        var conversations = mock(ConversationService.class);
        var rewriter = mock(QueryRewriter.class);
        var terms = mock(TermMapper.class);
        var router = new CampusQuestionRouter(new CampusService(
                new MockCampusDataProvider(new ObjectMapper())));
        var service = new RagService(embeddings, chatModel, retrieval, conversations, rewriter,
                terms, new RagProperties(), Runnable::run, router);
        String question = "我的成绩是否满足软件工程学硕毕业学分要求？";
        String ruleQuery = "软件工程学硕毕业学分要求？";
        when(conversations.rewriteHistory(1L)).thenReturn(List.of(
                new ChatMessage(1L, "assistant", "上次模拟成绩是91分", List.of(), null)));
        when(terms.normalize(ruleQuery)).thenReturn(ruleQuery);
        when(rewriter.rewrite(eq(ruleQuery), eq(List.of()))).thenReturn(
                new QueryRewriter.Result(ruleQuery, List.of(ruleQuery), false));
        when(embeddings.embed(ruleQuery)).thenReturn(new float[]{0.1f});
        Source source = new Source(1, 16L, "培养方案", 219,
                "软件工程课程总学分不低于23学分", 0.8);
        when(retrieval.retrieve(anyList(), anyList(), eq(ruleQuery))).thenReturn(
                new RetrievalOutcome(List.of(), List.of(source), true, 0.8, "pass", 1,
                        Map.of("vector", 1), List.of(), 0, 1));
        doAnswer(call -> {
            ChatClient.Listener listener = call.getArgument(2);
            listener.onDelta("课程总学分至少23学分 [1]。");
            listener.onDone();
            return null;
        }).when(chatModel).stream(anyString(), anyString(), any());
        List<String> events = new ArrayList<>();

        var result = service.chat(1L, question, (event, data) -> events.add(event));

        assertEquals("campus_rule_mix", result.doneReason());
        assertEquals(1, result.llmCalls());
        assertTrue(result.answer().contains("虚构学生"));
        assertTrue(result.answer().contains("课程总学分至少23学分 [1]"));
        assertEquals(List.of("meta", "sources", "delta", "delta", "done"), events);
        ArgumentCaptor<String> modelPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).stream(anyString(), modelPrompt.capture(), any());
        assertFalse(modelPrompt.getValue().contains("91分"));
        assertFalse(modelPrompt.getValue().contains("已通过 8 学分"));
        verify(rewriter).rewrite(eq(ruleQuery), eq(List.of()));
    }

    @Test
    void mixedQuestionWithNoRuleEvidenceStillShowsMockDataButMakesNoModelCall() throws Exception {
        var embeddings = mock(EmbeddingClient.class);
        var chatModel = mock(ChatClient.class);
        var retrieval = mock(RetrievalEngine.class);
        var conversations = mock(ConversationService.class);
        var rewriter = mock(QueryRewriter.class);
        var terms = mock(TermMapper.class);
        var router = new CampusQuestionRouter(new CampusService(
                new MockCampusDataProvider(new ObjectMapper())));
        var service = new RagService(embeddings, chatModel, retrieval, conversations, rewriter,
                terms, new RagProperties(), Runnable::run, router);
        String question = "我的成绩是否满足软件工程学硕毕业学分要求？";
        String ruleQuery = "软件工程学硕毕业学分要求？";
        when(terms.normalize(ruleQuery)).thenReturn(ruleQuery);
        when(rewriter.rewrite(anyString(), anyList())).thenReturn(
                new QueryRewriter.Result(ruleQuery, List.of(ruleQuery), false));
        when(embeddings.embed(ruleQuery)).thenReturn(new float[]{0.1f});
        when(retrieval.retrieve(anyList(), anyList(), eq(ruleQuery))).thenReturn(
                new RetrievalOutcome(List.of(), List.of(), false, 0.1, "rejected", 0,
                        Map.of(), List.of(), 0, 1));

        var result = service.chat(1L, question, (event, data) -> {});

        assertEquals("campus_rule_no_sources", result.doneReason());
        assertTrue(result.answer().contains("虚构学生"));
        assertTrue(result.answer().contains("未找到足以回答"));
        verifyNoInteractions(chatModel);
    }

    @Test
    void publicRuleQuestionKeepsPublicContextButExcludesEarlierMockTurns() throws Exception {
        var embeddings = mock(EmbeddingClient.class);
        var chatModel = mock(ChatClient.class);
        var retrieval = mock(RetrievalEngine.class);
        var conversations = mock(ConversationService.class);
        var rewriter = mock(QueryRewriter.class);
        var terms = mock(TermMapper.class);
        var router = new CampusQuestionRouter(new CampusService(
                new MockCampusDataProvider(new ObjectMapper())));
        var service = new RagService(embeddings, chatModel, retrieval, conversations, rewriter,
                terms, new RagProperties(), Runnable::run, router);
        String question = "软件工程学硕学位课至少多少学分？";
        when(conversations.rewriteHistory(1L)).thenReturn(List.of(
                new ChatMessage(0L, "assistant", "**Mock 学分分析**：旧数据", List.of(), null),
                new ChatMessage(1L, "user", "我的成绩是多少？", List.of(), null),
                new ChatMessage(2L, "assistant", "**Mock 成绩**：CS501 91分", List.of(), null),
                new ChatMessage(3L, "user", "培养方案适用哪一届？", List.of(), null),
                new ChatMessage(4L, "assistant", "适用于2025级 [1]。", List.of(), null),
                new ChatMessage(5L, "user", "我的成绩是否满足软件工程学硕毕业学分要求？", List.of(), null),
                new ChatMessage(6L, "assistant", "**虚构学生的模拟学业数据**：8学分", List.of(), null)));
        when(terms.normalize(question)).thenReturn(question);
        when(rewriter.rewrite(eq(question), anyList())).thenReturn(
                new QueryRewriter.Result(question, List.of(question), false));
        when(embeddings.embed(question)).thenReturn(new float[]{0.1f});
        Source source = new Source(1, 16L, "培养方案", 219,
                "软件工程学硕学位课不少于12学分", 0.8);
        when(retrieval.retrieve(anyList(), anyList(), eq(question))).thenReturn(
                new RetrievalOutcome(List.of(), List.of(source), true, 0.8, "pass", 1,
                        Map.of("vector", 1), List.of(), 0, 1));
        doAnswer(call -> {
            ChatClient.Listener listener = call.getArgument(2);
            listener.onDelta("学位课不少于12学分 [1]。");
            listener.onDone();
            return null;
        }).when(chatModel).stream(anyString(), anyString(), any());

        var result = service.chat(1L, question, (event, data) -> {});

        assertEquals("ok", result.doneReason());
        verify(rewriter).rewrite(question, List.of("用户：培养方案适用哪一届？", "助手：适用于2025级 [1]。"));
        ArgumentCaptor<String> modelPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).stream(anyString(), modelPrompt.capture(), any());
        assertTrue(modelPrompt.getValue().contains("培养方案适用哪一届？"));
        assertFalse(modelPrompt.getValue().contains("91分"));
        assertFalse(modelPrompt.getValue().contains("我的成绩"));
        assertFalse(modelPrompt.getValue().contains("8学分"));
    }
}
