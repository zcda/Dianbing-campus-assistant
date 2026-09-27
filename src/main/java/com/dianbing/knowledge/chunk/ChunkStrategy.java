package com.dianbing.knowledge.chunk;

import java.util.List;

/**
 * 切片策略扩展点（设计文档 D5）：让"切片策略对比实验"成为可能，替代隐式单一策略。
 * 本轮实现两个（够做 A/B）：heading（标题感知改良版）/ fixed（纯定长对照组）。
 */
public interface ChunkStrategy {

    /** 策略名，对应 rag.chunk.strategy 配置 */
    String name();

    List<String> split(String content, ChunkBudget budget);
}
