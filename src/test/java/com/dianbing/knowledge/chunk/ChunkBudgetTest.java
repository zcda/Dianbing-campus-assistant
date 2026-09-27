package com.dianbing.knowledge.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分块预算与策略的纯逻辑测试（设计文档 D5 验收）：
 * - 构造期校验：0 ≤ overlap < maxChars、maxChars ≤ 8192、toleranceFactor ∈ [1,8] —— 非法配置启动失败；
 * - heading 策略：预算内不切；容忍范围内整节保留（"切开语义单元的代价高于超出目标"）；
 * - 等比重叠：默认 overlap = maxChars / 8。
 */
class ChunkBudgetTest {

    @Test
    void 非法配置_启动即抛异常并给出合法区间() {
        IllegalStateException e1 = assertThrows(IllegalStateException.class,
                () -> new ChunkBudget(500, 500, 3));
        assertTrue(e1.getMessage().contains("overlap"));
        assertThrows(IllegalStateException.class, () -> new ChunkBudget(500, -1, 3));
        assertThrows(IllegalStateException.class, () -> new ChunkBudget(9000, 100, 3));
        assertThrows(IllegalStateException.class, () -> new ChunkBudget(500, 50, 0));
        assertThrows(IllegalStateException.class, () -> new ChunkBudget(500, 50, 9));
    }

    @Test
    void overlap缺省_按块大小等比取八分之一() {
        assertEquals(128, ChunkBudget.of(1024, null, 3).overlapChars());
        assertEquals(62, ChunkBudget.of(500, null, 3).overlapChars());
        assertEquals(3072, ChunkBudget.of(1024, null, 3).toleranceChars());
        assertEquals(8192, ChunkBudget.of(4096, null, 3).toleranceChars()); // 封顶
    }

    @Test
    void heading策略_容忍范围内的节整节保留() {
        HeadingChunkStrategy strategy = new HeadingChunkStrategy();
        ChunkBudget budget = ChunkBudget.of(500, 50, 3);
        // 700 字节的节在 tolerance(1500) 内：不切
        String section = "## 标题\n" + "内存".repeat(350);
        List<String> pieces = strategy.split(section, budget);
        assertEquals(1, pieces.size());
        assertEquals(section.strip().length(), pieces.get(0).length());
    }

    @Test
    void heading策略_超容忍的节按句末边界切并带重叠() {
        HeadingChunkStrategy strategy = new HeadingChunkStrategy();
        ChunkBudget budget = ChunkBudget.of(200, 20, 2); // tolerance=400
        String body = ("句子内容占位。").repeat(150); // 900 字，必须切
        String section = "## 标题\n" + body;
        List<String> pieces = strategy.split(section, budget);
        assertTrue(pieces.size() >= 3);
        for (String piece : pieces) {
            // 超预算但 ≤ tolerance；或 ≤ maxChars
            assertTrue(piece.length() <= budget.toleranceChars(),
                    "块长 " + piece.length() + " 超过硬上限 " + budget.toleranceChars());
        }
        // 相邻块重叠（除最后一块外，块尾与下一块开头有重叠内容）
        for (int i = 0; i + 1 < pieces.size(); i++) {
            String tail = pieces.get(i).substring(pieces.get(i).length() - 20);
            String nextHead = pieces.get(i + 1).substring(0, Math.min(20, pieces.get(i + 1).length()));
            assertTrue(nextHead.contains(tail.substring(Math.max(0, tail.length() - 5))) || i == pieces.size() - 2,
                    "相邻块应有重叠");
        }
    }

    @Test
    void heading策略_标题行保留在块内() {
        HeadingChunkStrategy strategy = new HeadingChunkStrategy();
        ChunkBudget budget = ChunkBudget.of(500, 50, 3);
        List<String> pieces = strategy.split("前言内容\n\n## 第一节\n内容甲\n\n## 第二节\n内容乙", budget);
        assertEquals(3, pieces.size());
        assertTrue(pieces.get(1).startsWith("## 第一节"));
        assertTrue(pieces.get(2).startsWith("## 第二节"));
    }

    @Test
    void fixed策略_纯定长加重叠() {
        FixedWindowChunkStrategy strategy = new FixedWindowChunkStrategy();
        ChunkBudget budget = ChunkBudget.of(100, 20, 3);
        String text = "字".repeat(250);
        List<String> pieces = strategy.split(text, budget);
        assertTrue(pieces.size() >= 3);
        assertEquals(100, pieces.get(0).length());
        // 相邻块重叠 20：第二块从 80 开始
        assertEquals(text.substring(80, 180), pieces.get(1));
    }

    @Test
    void 空与null内容_返回空列表() {
        assertEquals(List.of(), new HeadingChunkStrategy().split(null, ChunkBudget.of(500, 50, 3)));
        assertEquals(List.of(), new HeadingChunkStrategy().split("  \n\n ", ChunkBudget.of(500, 50, 3)));
        assertEquals(List.of(), new FixedWindowChunkStrategy().split("", ChunkBudget.of(500, 50, 3)));
    }
}
