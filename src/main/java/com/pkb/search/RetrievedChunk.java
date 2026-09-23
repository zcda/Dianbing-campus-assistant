package com.pkb.search;

import com.pkb.search.channel.SearchChannelType;

import java.util.EnumSet;

/**
 * 检索候选实体（设计文档 D1）：贯穿"通道召回 → 去重 → 融合 → 打分 → 闸门 → 合并"全链路。
 * Source 降级为"送给前端与 Prompt 的视图"，由最终候选映射而来。
 */
public class RetrievedChunk {

    private final long noteId;
    private final int seq;
    private final String noteTitle;
    private final String content;
    private RuleReference rule;

    /** 余弦相似度（向量通道），可空 */
    private Double vectorScore;
    /** ts_rank（关键词通道），可空 */
    private Double keywordScore;
    /** RRF 融合分，可空 */
    private Double rrfScore;
    /** 闸门读的分（由 ScoreFill 填充），可空 */
    private Double gateScore;
    /** 命中通道集合，用于归因与"多路命中"加成 */
    private final EnumSet<SearchChannelType> hitChannels = EnumSet.noneOf(SearchChannelType.class);

    public RetrievedChunk(long noteId, int seq, String noteTitle, String content) {
        this.noteId = noteId;
        this.seq = seq;
        this.noteTitle = noteTitle;
        this.content = content;
    }

    public long noteId() { return noteId; }
    public int seq() { return seq; }
    public String noteTitle() { return noteTitle; }
    public String content() { return content; }
    public RuleReference rule() { return rule; }
    public RetrievedChunk rule(RuleReference rule) { this.rule = rule; return this; }

    public Double vectorScore() { return vectorScore; }
    public RetrievedChunk vectorScore(Double vectorScore) { this.vectorScore = vectorScore; return this; }

    public Double keywordScore() { return keywordScore; }
    public RetrievedChunk keywordScore(Double keywordScore) { this.keywordScore = keywordScore; return this; }

    public Double rrfScore() { return rrfScore; }
    public RetrievedChunk rrfScore(Double rrfScore) { this.rrfScore = rrfScore; return this; }

    public Double gateScore() { return gateScore; }
    public RetrievedChunk gateScore(Double gateScore) { this.gateScore = gateScore; return this; }

    public EnumSet<SearchChannelType> hitChannels() { return hitChannels; }

    /** 合并同一 (noteId, seq) 在不同通道的命中：分数取各自非空值、通道取并集（Dedup 专用） */
    public void mergeFrom(RetrievedChunk other) {
        if (other.vectorScore != null && vectorScore == null) vectorScore = other.vectorScore;
        if (other.keywordScore != null && keywordScore == null) keywordScore = other.keywordScore;
        if (other.rrfScore != null && rrfScore == null) rrfScore = other.rrfScore;
        if (other.gateScore != null && gateScore == null) gateScore = other.gateScore;
        hitChannels.addAll(other.hitChannels);
        if (rule == null) rule = other.rule;
    }

    /** 检索结果排序兜底用的综合分（优先级与展示一致） */
    public double bestScore() {
        if (rrfScore != null) return rrfScore;
        if (vectorScore != null) return vectorScore;
        if (keywordScore != null) return keywordScore;
        return 0;
    }

    /** 切片唯一标识 "noteId:seq"，与评估集 expected_chunks 格式对齐 */
    public String ref() { return noteId + ":" + seq; }
}
