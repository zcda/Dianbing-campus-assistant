package com.dianbing.qa.retrieval.channel;

import com.dianbing.qa.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 单个通道一次召回的原始结果（设计文档 D1）。
 * results 按名次有序（rank = 下标从 0 开始），RRF 融合依赖的是这份原始名次，
 * 而不是去重后的候选集 —— 否则"被两路同时命中"的信息会丢。
 */
public record SearchChannelResult(SearchChannelType type, List<RetrievedChunk> results) {

    public SearchChannelResult {
        results = List.copyOf(results);
    }
}
