package com.pkb.search.channel;

/**
 * 一次子问题检索的上下文：贯穿"各通道召回 → 后置处理器链 → 闸门判定"。
 * 闸门处理器把 gateScore / gateDecision 回写到这里，供引擎聚合归因。
 */
public class SearchContext {

    private final String query;
    private final String scopeQuery;
    private final float[] queryVector;

    /** 闸门回写：本批判定的最高分；无分可读时为 null */
    private Double gateScore;
    /** 闸门回写：pass | rejected | allow_no_score；闸门关闭时为 null */
    private String gateDecision;
    /** 闸门回写：本环耗时（ms），供 meta timings 归因 */
    private long gateElapsedMs;

    public SearchContext(String query, float[] queryVector) {
        this(query, queryVector, query);
    }

    public SearchContext(String query, float[] queryVector, String scopeQuery) {
        this.query = query;
        this.queryVector = queryVector;
        this.scopeQuery = scopeQuery;
    }

    public String query() { return query; }
    public String scopeQuery() { return scopeQuery; }
    public float[] queryVector() { return queryVector; }

    public Double gateScore() { return gateScore; }
    public void gateScore(Double gateScore) { this.gateScore = gateScore; }

    public String gateDecision() { return gateDecision; }
    public void gateDecision(String gateDecision) { this.gateDecision = gateDecision; }

    public long gateElapsedMs() { return gateElapsedMs; }
    public void gateElapsedMs(long gateElapsedMs) { this.gateElapsedMs = gateElapsedMs; }
}
