package com.dianbing.knowledge.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标题感知切片（现状的改良版，设计文档 D5）：
 * <ol>
 *   <li>按 ATX 标题（#{1..6} 任意级别）分节，标题行保留在块内（有利于检索命中）；
 *       首个标题之前的正文独立成块 —— 实测修正：笔记 14（91k 字八股汇总）用 "### N." 三级标题组织问答对，
 *       只认 "## " 时整篇退化为定长切，题目与答案被切进相邻块，检索命中块不含答案；</li>
 *   <li>预算内不切；超预算的节优先按句末标点回退/前探切分（回退距离上限 = overlapChars），
 *       在 toleranceChars 容忍范围内的节宁可整节保留，也不把一个表格/代码块/小节切两半；</li>
 *   <li>相邻块等比重叠 overlapChars。</li>
 * </ol>
 */
@Component
public class HeadingChunkStrategy implements ChunkStrategy {

    private static final java.util.regex.Pattern ATX_HEADING = java.util.regex.Pattern.compile("^#{1,6} ");

    @Override
    public String name() { return "heading"; }

    @Override
    public List<String> split(String content, ChunkBudget budget) {
        List<String> chunks = new ArrayList<>();
        for (String section : splitByHeading(content == null ? "" : content)) {
            String text = section.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() <= budget.maxChars()) {
                chunks.add(text);
            } else if (text.length() <= budget.toleranceChars()) {
                // 超预算但在容忍范围内：整节保留（"切开语义单元的代价高于超出目标"）
                chunks.add(text);
            } else {
                chunks.addAll(splitLongSection(text, budget));
            }
        }
        return chunks;
    }

    /** 按任意级别 ATX 标题行切块；没有标题时整篇作为一节 */
    private List<String> splitByHeading(String content) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (ATX_HEADING.matcher(line).find()) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        sections.add(current.toString());
        return sections;
    }

    /** 超长节定长切 + 句末边界回退 + 等比重叠 */
    private List<String> splitLongSection(String text, ChunkBudget budget) {
        List<String> pieces = new ArrayList<>();
        int max = budget.maxChars();
        int overlap = budget.overlapChars();
        int tolerance = budget.toleranceChars();
        int start = 0;
        while (start < text.length()) {
            if (text.length() - start <= tolerance) {
                pieces.add(text.substring(start));
                break;
            }
            int base = start + max;
            int cut = -1;
            // ① 从 base 向前回退找句末标点，最大回退距离 = overlap
            int backLimit = Math.max(start + 1, base - overlap);
            for (int i = base; i > backLimit; i--) {
                if (ChunkBudget.isSentenceEnd(text.charAt(i - 1))) {
                    cut = i;
                    break;
                }
            }
            // ② 回退没找到 → 向后前探（宁可超一点），上限 tolerance
            if (cut < 0) {
                int forwardLimit = Math.min(text.length(), start + tolerance);
                for (int i = base + 1; i <= forwardLimit; i++) {
                    if (ChunkBudget.isSentenceEnd(text.charAt(i - 1))) {
                        cut = i;
                        break;
                    }
                }
            }
            // ③ 都没有 → 硬切
            if (cut < 0) {
                cut = base;
            }
            pieces.add(text.substring(start, cut));
            int next = cut - overlap;
            if (next <= start) {
                // 兜底：保证每块至少前进 max/2，避免 overlap 配置极端时死循环
                next = start + Math.max(1, max / 2);
            }
            start = next;
        }
        return pieces;
    }
}
