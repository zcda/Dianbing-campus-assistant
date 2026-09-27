package com.dianbing.qa.retrieval;

import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.qa.retrieval.channel.SearchChannel;
import com.dianbing.qa.retrieval.channel.SearchChannelResult;
import com.dianbing.qa.retrieval.channel.SearchChannelType;
import com.dianbing.qa.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalAggregationTest {
    @Test
    void duplicateHitsWithinOneSubqueryDoNotOutrankStrongerEvidence() {
        SearchChannel channel = new SearchChannel() {
            public SearchChannelType type() { return SearchChannelType.VECTOR; }
            public boolean isEnabled() { return true; }
            public SearchChannelResult search(SearchContext context) {
                return new SearchChannelResult(type(), List.of(
                        new RetrievedChunk(1, 1, "A", "weak").vectorScore(0.1),
                        new RetrievedChunk(1, 1, "A", "better").vectorScore(0.2),
                        new RetrievedChunk(1, 2, "B", "strong").vectorScore(0.9)));
            }
        };
        RetrievalEngine engine = new RetrievalEngine(List.of(channel), List.of(), new RagProperties(), null, Runnable::run);
        engine.afterPropertiesSet();
        assertEquals("1:2", engine.retrieve(List.of("question"), List.of(new float[0])).chunks().get(0).ref());
    }

    @Test
    void repeatedReferenceKeepsBestSubqueryScore() {
        SearchChannel channel = new SearchChannel() {
            public SearchChannelType type() { return SearchChannelType.VECTOR; }
            public boolean isEnabled() { return true; }
            public SearchChannelResult search(SearchContext context) {
                double score = context.query().equals("first") ? 0.2 : 0.8;
                return new SearchChannelResult(type(), List.of(
                        new RetrievedChunk(1, 1, "A", "evidence").vectorScore(score)));
            }
        };
        RetrievalEngine engine = new RetrievalEngine(List.of(channel), List.of(), new RagProperties(), null, Runnable::run);
        engine.afterPropertiesSet();
        assertEquals(0.8, engine.retrieve(List.of("first", "second"),
                List.of(new float[0], new float[0])).chunks().get(0).bestScore());
    }
}
