package com.dianbing.eval;

import com.dianbing.knowledge.chunk.ChunkBudget;
import com.dianbing.knowledge.chunk.HeadingChunkStrategy;
import com.dianbing.knowledge.chunk.RuleChunkStrategy;
import com.dianbing.knowledge.ingest.TextCleaner;
import com.dianbing.knowledge.ingest.TikaDocumentParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 金标准必须在真实 PDF 的实际切片中出现，避免凭空编造评测题。 */
class CampusEvalCorpusTest {
    @Test
    void refusalHoldoutTopicsAreAbsentFromBundledRulePdf() throws Exception {
        Path pdf = Path.of("doc", "2026年全日制学术学位硕士研究生培养方案_261716157506.pdf");
        String text;
        try (var in = Files.newInputStream(pdf)) {
            text = new TextCleaner().clean(new TikaDocumentParser().parse(in), true);
        }
        var rows = Files.readAllLines(Path.of("src/test/resources/eval/campus_refusal_holdout_v1.jsonl"));
        assertEquals(12, rows.size(), "留出集题数变化需人工复核");
        var mapper = new ObjectMapper();
        for (String row : rows) {
            var sample = mapper.readTree(row);
            String term = sample.path("absentTerm").asText();
            assertFalse(term.isBlank(), "库外样本必须标明缺席的主题词");
            assertFalse(text.contains(term), sample.path("queryId").asText() + " 的主题已出现在 PDF：" + term);
        }
    }

    @Test
    void allExpectedEvidenceExistsInBundledPdfChunks() throws Exception {
        assertCorpus("/eval/campus_eval_v1.jsonl", 10, 3, 2);
    }

    @Test
    void expandedEvidenceExistsInBundledPdfChunks() throws Exception {
        assertCorpus("/eval/campus_eval_v2.jsonl", 30, 5, 5);
    }

    private void assertCorpus(String resource, int minAnswers, int strictRefusals, int scopeWarnings)
            throws Exception {
        List<EvalSample> samples = EvalSupport.load(resource);
        Map<String, List<String>> chunksByFile = new HashMap<>();
        for (EvalSample sample : samples) {
            for (EvalSample.EvidenceSpec evidence : sample.expectedEvidence()) {
                List<String> chunks = chunksByFile.get(evidence.sourceFilename());
                if (chunks == null) {
                    Path path = Path.of("doc", evidence.sourceFilename());
                    assertTrue(Files.isRegularFile(path), "缺少真实语料：" + path);
                    String raw;
                    try (var in = Files.newInputStream(path)) {
                        raw = new TikaDocumentParser().parse(in);
                    }
                    String cleaned = new TextCleaner().clean(raw, true);
                    chunks = new RuleChunkStrategy(new HeadingChunkStrategy())
                            .split(cleaned, ChunkBudget.of(1024, 128, 3));
                    chunksByFile.put(evidence.sourceFilename(), chunks);
                }
                assertTrue(chunks.stream().anyMatch(c -> c.contains(evidence.context())
                                && c.contains(evidence.contains())),
                        sample.queryId() + " 对应原文在切片中不存在：" + evidence.contains());
            }
        }
        assertTrue(samples.stream().filter(EvalSample::requiresRag).count() >= minAnswers);
        assertEquals(strictRefusals, samples.stream().filter(EvalSample::strictRefusal).count(),
                "无依据拒答样本的数量发生变化，请检查过召回率分母");
        assertEquals(scopeWarnings, samples.stream().filter(EvalSample::scopeWarning).count(),
                "适用范围近邻样本应单独复核");
        assertTrue(samples.stream().filter(EvalSample::requiresRag)
                .allMatch(s -> "answer".equals(s.expectedBehavior()) && !s.requiredFacts().isEmpty()),
                "应答题需要必要事实金标准");
    }
}
