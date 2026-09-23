package com.pkb.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯定长切片（对照组，设计文档 D5）：不做任何结构感知，整篇按 maxChars 定长 + overlapChars 重叠。
 * 用于回答"按结构切到底比定长好多少"（heading vs fixed A/B 实验的基线组）。
 */
@Component
public class FixedWindowChunkStrategy implements ChunkStrategy {

    @Override
    public String name() { return "fixed"; }

    @Override
    public List<String> split(String content, ChunkBudget budget) {
        List<String> chunks = new ArrayList<>();
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            return chunks;
        }
        if (text.length() <= budget.maxChars()) {
            chunks.add(text);
            return chunks;
        }
        int max = budget.maxChars();
        int overlap = budget.overlapChars();
        for (int start = 0; start < text.length(); start += max - overlap) {
            int end = Math.min(start + max, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }
}
