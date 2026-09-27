package com.dianbing.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.infrastructure.llm.EmbeddingClient;
import com.dianbing.qa.retrieval.RetrievalEngine;
import com.dianbing.testinfra.RequiresPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run explicitly with -Deval.online=true after importing the verified public-rule corpus. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@RequiresPostgres
@EnabledIfSystemProperty(named = "eval.online", matches = "true")
class RefusalHoldoutTest {
    private static final List<String> DATASETS = List.of(
            "/eval/campus_refusal_holdout_v1.jsonl", "/eval/campus_refusal_holdout_v2.jsonl",
            "/eval/campus_refusal_near_neighbor_v1.jsonl", "/eval/campus_refusal_unseen_v3.jsonl");

    @Autowired private JdbcClient db;
    @Autowired private EmbeddingClient embeddingClient;
    @Autowired private RetrievalEngine retrievalEngine;
    @Autowired private RagProperties props;

    record Holdout(String queryId, String query, String absentTerm, String note) { }

    @Test
    void unseenOutOfCorpusQuestionsAreRejectedByTheProductionRetrievalGate() throws Exception {
        List<Holdout> holdouts = new ArrayList<>();
        for (String dataset : DATASETS) {
            List<Holdout> loaded = load(dataset);
            assertEquals(dataset.contains("near_neighbor") || dataset.contains("unseen_v3") ? 8 : 12, loaded.size(),
                    dataset + " 题数变化需人工复核分母");
            holdouts.addAll(loaded);
        }
        Long active = db.sql("SELECT count(*) FROM note WHERE status='active' AND index_ready=TRUE")
                .query(Long.class).single();
        assertTrue(active != null && active > 0, "先导入并索引已核验的真实规则 PDF");

        for (Holdout sample : holdouts) {
            Long mentions = db.sql("""
                            SELECT count(*) FROM chunk c JOIN note n ON n.id=c.note_id
                            WHERE n.status='active' AND n.index_ready=TRUE
                              AND (n.effective_from IS NULL OR n.effective_from<=CURRENT_DATE)
                              AND (n.effective_to IS NULL OR n.effective_to>=CURRENT_DATE)
                              AND position(:term in c.content)>0
                            """)
                    .param("term", sample.absentTerm()).query(Long.class).single();
            assertEquals(0L, mentions, sample.queryId() + " 的库外标签已失效：" + sample.absentTerm());
        }

        List<String> failures = new ArrayList<>();
        for (Holdout sample : holdouts) {
            float[] vector = embeddingClient.embed(sample.query());
            var result = retrievalEngine.retrieve(List.of(sample.query()), List.of(vector));
            System.out.printf("[%s] gate=%s score=%s sources=%d%n", sample.queryId(),
                    result.gateDecision(), result.gateScore(), result.sources().size());
            if (!result.sources().isEmpty()) {
                failures.add(sample.queryId() + "(" + sample.absentTerm() + ", score="
                        + result.gateScore() + ")");
            }
        }
        assertTrue(failures.isEmpty(), "留出集过召回 " + failures.size() + "/" + holdouts.size()
                + "；当前闸门=" + props.getEvidence().getMinGateScore() + "：" + failures);
    }

    private List<Holdout> load(String dataset) throws Exception {
        var mapper = new ObjectMapper();
        var rows = new ArrayList<Holdout>();
        try (var input = RefusalHoldoutTest.class.getResourceAsStream(dataset)) {
            if (input == null) throw new IllegalStateException("找不到拒答留出集：" + dataset);
            var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) rows.add(mapper.readValue(line, Holdout.class));
            }
        }
        return rows;
    }
}
