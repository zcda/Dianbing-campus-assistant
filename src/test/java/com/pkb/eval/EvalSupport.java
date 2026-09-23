package com.pkb.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pkb.search.Source;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 评测测试共享工具：加载评估集 + 检索指标原语（纯函数，便于各评测共用同一套口径）。 */
final class EvalSupport {

    private EvalSupport() {
    }

    /** 从 classpath 读 jsonl 评估集，每行一条样本 */
    static List<EvalSample> load(String classpathResource) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<EvalSample> samples = new ArrayList<>();
        try (InputStream in = EvalSupport.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("找不到评估集：" + classpathResource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    samples.add(mapper.readValue(line, EvalSample.class));
                }
            }
        }
        return samples;
    }

    /** 防止旧技术数据集或重切片后的陈旧 ID 被当作校园规则评测结果。 */
    static void requireCurrentEvidence(JdbcClient db, List<EvalSample> samples) {
        List<String> refs = samples.stream().filter(EvalSample::requiresRag)
                .flatMap(s -> s.expectedChunks().stream()).distinct().toList();
        if (refs.isEmpty()) {
            throw new IllegalStateException("评测集没有任何已标注的必要证据，无法计算检索质量");
        }
        List<String> invalid = new ArrayList<>();
        for (String ref : refs) {
            String[] parts = ref.split(":", -1);
            if (parts.length != 2) {
                invalid.add(ref);
                continue;
            }
            try {
                Long count = db.sql("""
                                SELECT COUNT(*) FROM chunk c JOIN note n ON n.id=c.note_id
                                WHERE c.note_id=:noteId AND c.seq=:seq AND n.status='active'
                                  AND n.index_ready=TRUE AND (n.effective_from IS NULL OR n.effective_from <= CURRENT_DATE)
                                  AND (n.effective_to IS NULL OR n.effective_to >= CURRENT_DATE)
                                """)
                        .param("noteId", Long.parseLong(parts[0]))
                        .param("seq", Integer.parseInt(parts[1]))
                        .query(Long.class).single();
                if (count == null || count == 0) invalid.add(ref);
            } catch (NumberFormatException e) {
                invalid.add(ref);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("评测集期望证据并非当前有效规则或分块编号已变化："
                    + invalid.stream().limit(8).toList() + "。请导入真实校园规则并重新标注金标准。");
        }
    }

    /** 用文档名、学科上下文和原文片段解析金标准；重建索引后不依赖易变的 noteId:seq。 */
    static List<EvalSample> resolveEvidence(JdbcClient db, List<EvalSample> samples) {
        List<EvalSample> resolved = new ArrayList<>();
        for (EvalSample sample : samples) {
            List<String> refs = new ArrayList<>(sample.expectedChunks());
            for (EvalSample.EvidenceSpec evidence : sample.expectedEvidence()) {
                if (evidence.sourceFilename() == null || evidence.contains() == null || evidence.context() == null) {
                    throw new IllegalArgumentException(sample.queryId() + " 缺少 source_filename/contains/context");
                }
                var match = db.sql("""
                                SELECT c.note_id, c.seq FROM chunk c JOIN note n ON n.id=c.note_id
                                WHERE n.source_filename=:filename AND n.status='active' AND n.index_ready=TRUE
                                  AND c.content LIKE :needle AND c.content LIKE :context
                                ORDER BY c.seq LIMIT 1
                                """)
                        .param("filename", evidence.sourceFilename())
                        .param("needle", "%" + evidence.contains() + "%")
                        .param("context", "%" + evidence.context() + "%")
                        .query((rs, row) -> rs.getLong("note_id") + ":" + rs.getInt("seq"))
                        .optional();
                if (match.isEmpty()) {
                    throw new IllegalStateException("未找到评测金标准原文：" + sample.queryId()
                            + " / " + evidence.context() + " / " + evidence.contains()
                            + "。请重建索引后核对语料与标注。");
                }
                refs.add(match.orElseThrow());
            }
            resolved.add(sample.withExpectedChunks(refs.stream().distinct().toList()));
        }
        return resolved;
    }

    /** 把检索结果映射成 "noteId:seq" 引用串，与评估集的 expected_chunks 格式对齐 */
    static List<String> refs(List<Source> hits) {
        return hits.stream().map(h -> h.noteId() + ":" + h.seq()).toList();
    }

    static List<String> topK(List<String> retrieved, int k) {
        return retrieved.subList(0, Math.min(k, retrieved.size()));
    }

    /** Top-K 里是否至少命中一个期望切片 */
    static double hit(List<String> retrieved, Set<String> expected, int k) {
        return topK(retrieved, k).stream().anyMatch(expected::contains) ? 1.0 : 0.0;
    }

    /** Top-K 覆盖了多少比例的期望切片 */
    static double recall(List<String> retrieved, Set<String> expected, int k) {
        if (expected.isEmpty()) {
            return 0.0;
        }
        long found = topK(retrieved, k).stream().filter(expected::contains).distinct().count();
        return (double) found / expected.size();
    }

    /** 第一个命中期望切片的排名倒数；一个都没命中记 0 */
    static double mrr(List<String> retrieved, Set<String> expected) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** 期望切片里排名最靠前的那一条在检索结果中的位次（从 1 开始）；未命中记 0 */
    static int firstHitRank(List<String> retrieved, Set<String> expected) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 均值，null 值被跳过；全为 null 时返回 null（表示该指标无适用样本） */
    static Double mean(List<Double> values) {
        List<Double> valid = values.stream().filter(Objects::nonNull).toList();
        return valid.isEmpty() ? null : valid.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
