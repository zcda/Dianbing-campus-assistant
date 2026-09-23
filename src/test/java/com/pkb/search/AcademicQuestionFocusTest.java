package com.pkb.search;

import com.pkb.config.RagProperties;
import com.pkb.llm.EmbeddingClient;
import com.pkb.search.channel.SearchChannel;
import com.pkb.search.channel.SearchChannelResult;
import com.pkb.search.channel.SearchChannelType;
import com.pkb.search.channel.SearchContext;
import com.pkb.search.postprocessor.EvidenceGatePostProcessor;
import com.pkb.search.postprocessor.ScoreFillPostProcessor;
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
