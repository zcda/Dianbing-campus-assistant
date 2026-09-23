package com.pkb.eval;

import com.pkb.chunk.ChunkBudget;
import com.pkb.chunk.HeadingChunkStrategy;
import com.pkb.chunk.RuleChunkStrategy;
import com.pkb.ingest.TextCleaner;
import com.pkb.ingest.TikaDocumentParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖数据库的 chunk 质量评测：验证金标准事实与必要上下文是否落在同一个 chunk，
 * 并报告长度分布与过短块比例。它评估切分质量，不代表检索质量。
 */
class ChunkQualityEvalTest {

    @Test
    void campusGoldEvidenceKeepsFactsAndContextTogether() throws Exception {
        TextCleaner cleaner = new TextCleaner();
        TikaDocumentParser parser = new TikaDocumentParser();
        RuleChunkStrategy strategy = new RuleChunkStrategy(new HeadingChunkStrategy());
        List<Integer> lengths = new ArrayList<>();
        Map<String, List<String>> chunksByFile = new HashMap<>();
        int evidenceCount = 0;
        int sameChunk = 0;
        int contextKept = 0;

        for (String dataset : List.of("/eval/campus_eval_v1.jsonl", "/eval/campus_eval_v2.jsonl")) {
            for (EvalSample sample : EvalSupport.load(dataset)) {
                for (EvalSample.EvidenceSpec evidence : sample.expectedEvidence()) {
                List<String> chunks = chunksByFile.get(evidence.sourceFilename());
                if (chunks == null) {
                    Path path = Path.of("doc", evidence.sourceFilename());
                    String raw;
                    try (var input = Files.newInputStream(path)) {
                        raw = parser.parse(input);
                    }
                    chunks = strategy.split(cleaner.clean(raw, true), ChunkBudget.of(1024, 128, 3));
                    chunksByFile.put(evidence.sourceFilename(), chunks);
                    chunks.stream().map(String::length).forEach(lengths::add);
                }
                    evidenceCount++;
                    boolean found = false;
                    boolean foundWithContext = false;
                    for (String chunk : chunks) {
                        if (chunk.contains(evidence.contains())) {
                            found = true;
                            if (chunk.contains(evidence.context())) foundWithContext = true;
                        }
                    }
                    if (found) sameChunk++;
                    if (foundWithContext) contextKept++;
                }
            }
        }

        assertTrue(evidenceCount > 0, "校园评测集没有 chunk 金标准");
        assertTrue(sameChunk == evidenceCount,
                "存在被切断的金标准事实：" + sameChunk + "/" + evidenceCount);
        assertTrue(contextKept == evidenceCount,
                "存在缺少专业上下文的金标准 chunk：" + contextKept + "/" + evidenceCount);

        double average = lengths.stream().mapToInt(Integer::intValue).average().orElse(0);
        long tiny = lengths.stream().filter(length -> length < 80).count();
        double tinyRate = (double) tiny / lengths.size();
        System.out.printf("CHUNK_QUALITY evidence=%d sameChunk=%.1f%% context=%.1f%% avgChars=%.0f tinyRate=%.1f%%%n",
                evidenceCount, sameChunk * 100.0 / evidenceCount,
                contextKept * 100.0 / evidenceCount, average, tinyRate * 100);
    }
}
