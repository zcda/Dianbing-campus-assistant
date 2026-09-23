package com.pkb.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalSupportTest {
    @Test
    void missingGoldEvidenceFailsInsteadOfSkippingQualityEvaluation() {
        var sample = new EvalSample("C1", "校园规则是什么？", List.of(), List.of(), true, null);

        var failure = assertThrows(IllegalStateException.class,
                () -> EvalSupport.requireCurrentEvidence(null, List.of(sample)));

        assertTrue(failure.getMessage().contains("没有任何已标注的必要证据"));
    }

    @Test
    void malformedGoldReferenceFailsInsteadOfSkippingQualityEvaluation() {
        var sample = new EvalSample("C1", "校园规则是什么？", List.of("invalid-ref"), List.of(), true, null);

        var failure = assertThrows(IllegalStateException.class,
                () -> EvalSupport.requireCurrentEvidence(null, List.of(sample)));

        assertTrue(failure.getMessage().contains("分块编号已变化"));
    }
}
