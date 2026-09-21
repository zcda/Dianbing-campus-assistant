package com.pkb.search;

/** 检索命中的引用片段，index 从 1 开始，对应答案中的 [1][2] 角标 */
public record Source(int index, long noteId, String noteTitle, int seq, String content, double similarity) {
}
