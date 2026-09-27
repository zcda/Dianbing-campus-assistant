package com.dianbing.qa.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 角标校验纯逻辑测试（设计文档 D7：角标可映射率的判定原语，零 token、确定性） */
class CitationCheckTest {

    @Test
    void 正常角标全部可映射() {
        CitationCheck.Result r = CitationCheck.check("CMS 用标记-清除[1]，G1 划分 Region[2][3]", 3);
        assertEquals(3, r.total());
        assertEquals(3, r.valid());
        assertEquals(1.0, r.coverage(), 1e-9);
    }

    @Test
    void 越界角标记为无效() {
        CitationCheck.Result r = CitationCheck.check("答案[1][9]", 2);
        assertEquals(2, r.total());
        assertEquals(1, r.valid());
        assertEquals(0.5, r.coverage(), 1e-9);
    }

    @Test
    void 零角标单列_覆盖率为0() {
        CitationCheck.Result r = CitationCheck.check("没有任何引用的答案", 3);
        assertEquals(0, r.total());
        assertEquals(0, r.valid());
        assertEquals(0.0, r.coverage(), 1e-9);
    }

    @Test
    void 漏打角标的论断只是不被统计_不影响已有角标() {
        // F3 的度量口径：G1 那句没角标 → 只体现为支撑率分母不含它（total 只数打了角标的）
        CitationCheck.Result r = CitationCheck.check("CMS 使用标记-清除，会产生内存碎片。[2]\n* G1 把堆划分为 Region（无角标）", 2);
        assertEquals(1, r.total());
        assertEquals(1, r.valid());
    }
}
