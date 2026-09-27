package com.dianbing.qa.retrieval;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.infrastructure.llm.EmbeddingClient;
import com.dianbing.qa.retrieval.channel.SearchChannel;
import com.dianbing.qa.retrieval.channel.SearchChannelResult;
import com.dianbing.qa.retrieval.channel.SearchChannelType;
import com.dianbing.qa.retrieval.channel.SearchContext;
import com.dianbing.qa.retrieval.postprocessor.EvidenceGatePostProcessor;
import com.dianbing.qa.retrieval.postprocessor.ScoreFillPostProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicQuestionFocusTest {
    @Test
    void extractsTopicOnlyForCohortAndProgramQuestions() {
        assertEquals("每学年学费是多少钱？",
                AcademicQuestionFocus.extract("2026级软件工程学硕每学年学费是多少钱？").orElseThrow());
        assertTrue(AcademicQuestionFocus.extract("2026级软件工程本科每学年学费是多少钱？").isEmpty());
        assertTrue(AcademicQuestionFocus.extract(" ").isEmpty());
        assertEquals("教材购买链接是什么？", AcademicQuestionFocus.answerTarget(
                "培养方案里学位课的教材购买链接是什么？").orElseThrow());
        assertEquals("怎样申请人工调课？", AcademicQuestionFocus.answerTarget(
                "选课时间冲突后怎样申请人工调课？").orElseThrow());
    }

    @Test
    void rejectsHighFullQuestionScoreWhenActualTopicHasWeakEvidence() {
        var rejected = engine(0.52).retrieve(List.of("2026级软件工程学硕学费是多少？"),
                List.of(new float[] {1}));
        assertTrue(rejected.sources().isEmpty());
        assertEquals("rejected_focus", rejected.gateDecision());
        assertEquals(0.52, rejected.subQueryTrace().get(1).get("gateScore"));

        var passed = engine(0.58).retrieve(List.of("2026级软件工程学硕学费是多少？"),
                List.of(new float[] {1}));
        assertFalse(passed.sources().isEmpty());
        assertEquals("pass_focus", passed.subQueryTrace().get(1).get("gateDecision"));
    }

    private RetrievalEngine engine(double focusedScore) {
        RagProperties props = new RagProperties();
        props.getEvidence().setMinGateScore(0.60);
        props.getEvidence().setMinFocusScore(0.55);
        SearchChannel channel = new SearchChannel() {
            public SearchChannelType type() { return SearchChannelType.VECTOR; }
            public boolean isEnabled() { return true; }
            public SearchChannelResult search(SearchContext ctx) {
                double score = ctx.query().contains("学硕") ? 0.65 : focusedScore;
                return new SearchChannelResult(type(), List.of(
                        new RetrievedChunk(1, 1, "培养方案", "样例证据").vectorScore(score)));
            }
        };
        EmbeddingClient embeddings = texts -> texts.stream().map(s -> new float[] {1}).toList();
        var engine = new RetrievalEngine(List.of(channel), List.of(
                new ScoreFillPostProcessor(props), new EvidenceGatePostProcessor(props)), props, embeddings, Runnable::run);
        engine.afterPropertiesSet();
        return engine;
    }
}
