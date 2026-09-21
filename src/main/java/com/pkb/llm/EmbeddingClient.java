package com.pkb.llm;

import java.util.List;

/**
 * 向量化客户端抽象。换厂商只换实现，业务代码不感知。
 */
public interface EmbeddingClient {

    /** 批量向量化；返回顺序与入参一致，逐条校验维度 */
    List<float[]> embedBatch(List<String> texts);

    default float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        if (result.size() != 1) {
            throw new IllegalStateException("Embedding 返回数量不符: 期望 1 条, 实际 " + result.size() + " 条");
        }
        return result.get(0);
    }
}
