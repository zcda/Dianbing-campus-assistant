package com.dianbing.qa.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleScopeTest {
    @Test
    void explicitStudentTypeLimitsApplicableRules() {
        assertEquals("硕士", RuleScope.from("硕士研究生怎么办休学？").audience());
        assertEquals("本科", RuleScope.from("本科生如何缓考？").audience());
        assertEquals("博士", RuleScope.from("博士生能选课程吗？").audience());
        assertNull(RuleScope.from("怎么请假？").audience());
        assertEquals("2026", RuleScope.from("2026级全日制学硕学分要求？").academicYear());
        assertEquals("软件工程", RuleScope.from("2026级软件工程学硕培养方案").discipline());
        assertEquals("清水河校区", RuleScope.from("清水河校区本科生转专业").campus());
    }
}
