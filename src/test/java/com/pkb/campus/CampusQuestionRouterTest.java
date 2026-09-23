package com.pkb.campus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CampusQuestionRouterTest {
    private final CampusQuestionRouter router;

    CampusQuestionRouterTest() throws Exception {
        router = new CampusQuestionRouter(new CampusService(new MockCampusDataProvider(new ObjectMapper())));
    }

    @Test
    void routesPersonalGradeQuestionToMockData() {
        var answer = router.answer("我2025-秋的成绩是多少？").orElseThrow();
        assertEquals("grades_mock", answer.route());
        assertTrue(answer.text().contains("CS501"));
        assertTrue(answer.text().contains("虚构数据"));
    }

    @Test
    void routesScheduleAndGapWithExplicitLimits() {
        var schedule = router.answer("我的课表周二有什么课？").orElseThrow();
        assertEquals("schedule_mock", schedule.route());
        assertTrue(schedule.text().contains("分布式系统"));
        assertFalse(schedule.text().contains("高级算法"));
        var gap = router.answer("我还差多少学分才能毕业？").orElseThrow();
        assertEquals("credit_gap_mock", gap.route());
        assertTrue(gap.text().contains("尚缺 6 学分"));
        assertTrue(gap.text().contains("不是根据真实培养方案"));
        var exams = router.answer("我的考试安排是什么？").orElseThrow();
        assertEquals("exams_mock", exams.route());
        assertTrue(exams.text().contains("2026-12-18"));
        var recommendations = router.answer("我这学期推荐选什么课？").orElseThrow();
        assertEquals("recommendations_mock", recommendations.route());
        assertTrue(recommendations.text().contains("CS506"));
        assertTrue(recommendations.text().contains("CS505：先修课未全部通过"));
    }

    @Test
    void leavesPublicRuleQuestionsForRag() {
        assertTrue(router.answer("2026级硕士成绩合格要求是什么？").isEmpty());
        assertTrue(router.answer("我想知道学校的成绩规定").isEmpty());
        assertTrue(router.answer("学术活动有什么规定？").isEmpty());
    }

    @Test
    void plansMixedQuestionWithoutClaimingGraduationEligibility() {
        var answer = router.answer("我的成绩是否满足软件工程学硕毕业学分要求？").orElseThrow();
        assertTrue(answer.needsRuleEvidence());
        assertEquals("软件工程学硕毕业学分要求？", answer.ruleQuery());
        assertTrue(answer.text().contains("虚构"));
        assertTrue(answer.text().contains("不能据此判断真实毕业资格"));
    }

    @Test
    void routesExplicitFreeClassroomQueryToWeeklyMockTimetable() {
        var answer = router.answer("2026-秋周二第3-4节有哪些空教室？").orElseThrow();
        assertEquals("free_classrooms_mock", answer.route());
        assertTrue(answer.text().contains("A103"));
        assertTrue(answer.text().contains("B203"));
        assertFalse(answer.text().contains("A104"));
        assertTrue(answer.text().contains("不代表实时占用"));
        assertTrue(router.answer("有哪些空教室？").orElseThrow().text().contains("请指定星期和节次"));
    }
}
