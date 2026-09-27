package com.dianbing.qa.retrieval;

import java.util.List;

/**
 * 送给前端与 Prompt 的引用视图。
 * 改造 v1（设计文档 D1）：Source 从"检索结果本体"降级为视图，由最终候选 RetrievedChunk 映射而来，
 * index 从 1 开始对应答案角标 [1][2]；channels 记录命中通道，vectorScore 保留纯余弦分
 * （评测/阈值扫描用；keyword-only 命中时为 null，避免 ts_rank 污染分布统计）。
 * 旧 sources JSONB 存量缺新字段，反序列化时归一为空。
 */
public record Source(int index, long noteId, String noteTitle, int seq, String content,
                     double similarity, List<String> channels, Double vectorScore, RuleReference rule) {

    public Source {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    /** 兼容旧调用方的 6 参构造（similarity + 无通道信息） */
    public Source(int index, long noteId, String noteTitle, int seq, String content, double similarity) {
        this(index, noteId, noteTitle, seq, content, similarity, List.of(), null, null);
    }

    /** 兼容旧 JSONB 存量的 7 参构造 */
    public Source(int index, long noteId, String noteTitle, int seq, String content,
                  double similarity, List<String> channels) {
        this(index, noteId, noteTitle, seq, content, similarity, channels, null, null);
    }

    /** 由检索候选映射为视图：similarity 优先展示余弦分，缺失时退 ts_rank / rrf 分 */
    public static Source of(int index, RetrievedChunk chunk) {
        double sim = chunk.vectorScore() != null ? chunk.vectorScore()
                : chunk.keywordScore() != null ? chunk.keywordScore()
                : chunk.rrfScore() != null ? chunk.rrfScore() : 0.0;
        List<String> channels = chunk.hitChannels().stream()
                .map(Enum::name)
                .map(String::toLowerCase)
                .toList();
        return new Source(index, chunk.noteId(), chunk.noteTitle(), chunk.seq(), chunk.content(),
                sim, channels, chunk.vectorScore(), chunk.rule());
    }
}
