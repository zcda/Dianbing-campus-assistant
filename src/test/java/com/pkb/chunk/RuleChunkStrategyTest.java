package com.pkb.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleChunkStrategyTest {
    private final RuleChunkStrategy strategy = new RuleChunkStrategy(new HeadingChunkStrategy());

    @Test
    void keepsExceptionWithItsArticleAndCarriesChapterContext() {
        String rule = """
                第一章 学籍管理
                第一条 本科生申请休学，应提交书面申请。
                因病休学的，还须提交医院证明。
                第二条 研究生申请休学，由研究生院审核。
                但联合培养学生应另行报批。
                """;
        var pieces = strategy.split(rule, ChunkBudget.of(1024, 128, 3));
        assertEquals(2, pieces.size());
        assertTrue(pieces.get(0).contains("第一章 学籍管理"));
        assertTrue(pieces.get(0).contains("医院证明"));
        assertFalse(pieces.get(0).contains("研究生院"));
        assertTrue(pieces.get(1).contains("第二条"));
        assertTrue(pieces.get(1).contains("另行报批"));
    }

    @Test
    void fallsBackForDocumentsWithoutArticleNumbers() {
        var pieces = strategy.split("# 通知\n报名时间以官网公告为准。", ChunkBudget.of(1024, 128, 3));
        assertEquals(1, pieces.size());
        assertTrue(pieces.get(0).contains("报名时间"));
    }

    @Test
    void curriculumChunksKeepTheirDisciplineWhenLongSectionsSplit() {
        String text = "计算机科学与技术 全日制学术硕士培养方案\n四、学分与课程学习基本要求\n"
                + "计算机方向须完成学位课。".repeat(80)
                + "\n软件工程 全日制学术硕士培养方案\n四、学分与课程学习基本要求\n"
                + "软件工程方向另有要求。";
        var pieces = strategy.split(text, ChunkBudget.of(200, 20, 1));
        assertTrue(pieces.size() > 2);
        assertTrue(pieces.stream().filter(p -> p.contains("计算机方向"))
                .allMatch(p -> p.contains("计算机科学与技术")));
        assertTrue(pieces.stream().anyMatch(p -> p.contains("软件工程方向") && p.contains("软件工程 全日制")));
    }
}
