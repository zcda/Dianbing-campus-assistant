package com.dianbing.knowledge.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextCleanerRuleTest {
    @Test
    void repeatedPoliciesAcrossProgramsRemainSearchable() {
        String text = "第一章 总则\n\n第一条 应遵守规定。\n\n"
                + "总学分要求不低于 27学分。\n\n".repeat(4)
                + "— 12 —电子科技大学全日制学术学位硕士研究生培养方案\n";
        String cleaned = new TextCleaner().clean(text, true);
        assertEquals(4, cleaned.split("总学分要求不低于", -1).length - 1);
        assertFalse(cleaned.contains("— 12 —"));
        assertTrue(cleaned.contains("第一条"));
    }
}
