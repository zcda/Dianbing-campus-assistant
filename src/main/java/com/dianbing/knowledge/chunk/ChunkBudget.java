package com.dianbing.knowledge.chunk;

/**
 * 分块预算（设计文档 D5，设计依据来自 ragent 的 ChunkBudget）。
 *
 * <ul>
 *   <li>maxChars 是目标不是硬上限 —— "切开语义单元的代价高于超出目标"，
 *       另给 toleranceChars = maxChars × toleranceFactor（封顶 8192），超长块在容忍范围内整节保留；</li>
 *   <li>重叠按块大小等比给（默认 maxChars/8）—— "重叠不只冗余，它同时是回退寻找句末标点的最大距离"；</li>
 *   <li>构造期校验（不满足直接抛异常，启动失败）：
 *       0 ≤ overlapChars < maxChars、maxChars ≤ 8192、toleranceFactor ∈ [1,8] ——
 *       "否则超长块要到嵌入那一步才炸"。</li>
 * </ul>
 */
public final class ChunkBudget {

    public static final int MAX_SUPPORTED_CHARS = 8192;

    private final int maxChars;
    private final int overlapChars;
    private final int toleranceFactor;

    public ChunkBudget(int maxChars, int overlapChars, int toleranceFactor) {
        if (maxChars <= 0 || maxChars > MAX_SUPPORTED_CHARS) {
            throw new IllegalStateException(
                    "rag.chunk.max-chars 必须在 (0, %d] 区间，当前 %d".formatted(MAX_SUPPORTED_CHARS, maxChars));
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalStateException(
                    "rag.chunk.overlap-chars 必须满足 0 ≤ overlap < max-chars，当前 overlap=%d, max-chars=%d"
                            .formatted(overlapChars, maxChars));
        }
        if (toleranceFactor < 1 || toleranceFactor > 8) {
            throw new IllegalStateException(
                    "rag.chunk.tolerance-factor 必须在 [1,8] 区间，当前 %d".formatted(toleranceFactor));
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
        this.toleranceFactor = toleranceFactor;
    }

    public static ChunkBudget of(int maxChars, Integer overlapChars, int toleranceFactor) {
        int overlap = overlapChars != null ? overlapChars : maxChars / 8;
        return new ChunkBudget(maxChars, overlap, toleranceFactor);
    }

    /** 切分目标长度（软上限） */
    public int maxChars() { return maxChars; }

    /** 相邻块重叠：同时是"回退找句末标点的最大距离" */
    public int overlapChars() { return overlapChars; }

    /** 允许单块超出的硬上限：maxChars × toleranceFactor，封顶 8192 */
    public int toleranceChars() {
        long tolerance = (long) maxChars * toleranceFactor;
        return (int) Math.min(tolerance, MAX_SUPPORTED_CHARS);
    }

    /** 句末边界判定（标题策略回退切分用） */
    public static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                || c == '.' || c == '!' || c == '?' || c == ';';
    }
}
