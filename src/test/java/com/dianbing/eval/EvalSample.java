package com.dianbing.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 评估集的一条样本（当前规则 RAG 评测 JSONL 中的一行）。
 *
 * <p>expected_chunks / expected_nice 的元素是 "noteId:seq"，对应 chunk 表的 (note_id, seq)：
 * expected_chunks 是「必须召回」的核心证据，expected_nice 是「召回更好、缺了不扣分」的扩展证据。
 * requires_rag=false 表示库里本来就没有答案，正确行为是被闸门拦下、走兜底话术（done.reason=no_sources）。
 * expected_behavior: answer=有现行依据，refuse=当前库无依据，scope_warning=有近邻规则但不适用于问题对象。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalSample(
        @JsonProperty("query_id") String queryId,
        @JsonProperty("query") String query,
        @JsonProperty("expected_chunks") List<String> expectedChunks,
        @JsonProperty("expected_nice") List<String> expectedNice,
        @JsonProperty("requires_rag") boolean requiresRag,
        @JsonProperty("category") String category,
        @JsonProperty("note") String note,
        @JsonProperty("expected_evidence") List<EvidenceSpec> expectedEvidence,
        @JsonProperty("required_facts") List<String> requiredFacts,
        @JsonProperty("forbidden_facts") List<String> forbiddenFacts,
        @JsonProperty("expected_audience") String expectedAudience,
        @JsonProperty("expected_academic_year") String expectedAcademicYear,
        @JsonProperty("expected_behavior") String expectedBehavior) {

    public record EvidenceSpec(@JsonProperty("source_filename") String sourceFilename,
                               @JsonProperty("contains") String contains,
                               @JsonProperty("context") String context) { }

    public EvalSample {
        expectedChunks = expectedChunks == null ? List.of() : List.copyOf(expectedChunks);
        expectedNice = expectedNice == null ? List.of() : List.copyOf(expectedNice);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
        forbiddenFacts = forbiddenFacts == null ? List.of() : List.copyOf(forbiddenFacts);
        expectedBehavior = expectedBehavior == null ? (requiresRag ? "answer" : "refuse") : expectedBehavior;
    }

    /** 兼容无 category 的旧构造 */
    public EvalSample(String queryId, String query, List<String> expectedChunks,
                      List<String> expectedNice, boolean requiresRag, String note) {
        this(queryId, query, expectedChunks, expectedNice, requiresRag, null, note,
                List.of(), List.of(), List.of(), null, null, null);
    }

    public EvalSample withExpectedChunks(List<String> refs) {
        return new EvalSample(queryId, query, refs, expectedNice, requiresRag, category, note,
                expectedEvidence, requiredFacts, forbiddenFacts, expectedAudience, expectedAcademicYear,
                expectedBehavior);
    }

    public boolean strictRefusal() { return "refuse".equals(expectedBehavior); }
    public boolean scopeWarning() { return "scope_warning".equals(expectedBehavior); }
}
