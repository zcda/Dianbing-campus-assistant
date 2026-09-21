package com.pkb.chunk;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片策略（PRD §0.1）：
 * 1. 按 ## 二级标题切块，标题行保留在块内（有利于检索命中）；
 * 2. 首个 ## 之前的正文作为单独一块；
 * 3. 单块超过 500 字时定长再切，相邻块重叠 50 字。
 */
@Service
public class ChunkService {

    static final int MAX_CHUNK_CHARS = 500;
    static final int OVERLAP_CHARS = 50;

    public List<String> split(String content) {
        List<String> chunks = new ArrayList<>();
        for (String section : splitByHeading(content == null ? "" : content)) {
            String text = section.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() <= MAX_CHUNK_CHARS) {
                chunks.add(text);
                continue;
            }
            for (int start = 0; start < text.length(); start += MAX_CHUNK_CHARS - OVERLAP_CHARS) {
                int end = Math.min(start + MAX_CHUNK_CHARS, text.length());
                chunks.add(text.substring(start, end));
                if (end == text.length()) {
                    break;
                }
            }
        }
        return chunks;
    }

    /** 按 "## " 开头的行切块；没有二级标题时整篇作为一块 */
    private List<String> splitByHeading(String content) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("## ")) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        sections.add(current.toString());
        return sections;
    }
}
