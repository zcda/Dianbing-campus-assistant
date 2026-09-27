package com.dianbing.qa.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleBasedSplitterTest {
    private final RuleBasedSplitter splitter = new RuleBasedSplitter();

    @Test
    void c119SplitsIndependentRequirementsWithSharedMajor() {
        assertEquals(List.of(
                "软件工程学硕学位课至少多少？",
                "软件工程学硕课程总学分至少多少？"),
                splitter.split("软件工程学硕学位课和课程总学分分别至少多少？", 3));
    }

    @Test
    void keepsUnrelatedConjunctionAsOneQuestion() {
        assertEquals(List.of("生物医学工程学硕选课时需要综合考虑哪些因素？"),
                splitter.split("生物医学工程学硕选课时需要综合考虑哪些因素？", 3));
        assertEquals(List.of("研究方向和个人兴趣会影响选课吗？"),
                splitter.split("研究方向和个人兴趣会影响选课吗？", 3));
    }
}
