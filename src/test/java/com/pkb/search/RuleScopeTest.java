package com.pkb.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleScopeTest {
    @Test
    void explicitStudentTypeLimitsApplicableRules() {
        assertEquals("硕士生", RuleScope.from("硕士研究生怎么办休学？").audience());
        assertEquals("本科生", RuleScope.from("本科生如何缓考？").audience());
        assertEquals("博士生", RuleScope.from("博士生能选学硕课程吗？").audience());
        assertNull(RuleScope.from("怎么请假？").audience());
        assertEquals("2026", RuleScope.from("2026级全日制学硕学分要求？").academicYear());
    }
}
