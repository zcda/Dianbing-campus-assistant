package com.pkb.rewrite;

import java.util.List;

/**
 * 查询改写扩展点（设计文档 D6）：归一化后的问句 → 改写 + 多问句拆分。
 * 复合问题（"A 和 B 分别……"）单次向量检索只能命中一个语义中心，拆分是这类失败的解药。
 */
public interface QueryRewriter {

    Result rewrite(String question, List<String> historyLines);

    /**
     * @param rewritten    改写后的主问句（用于检索）
     * @param subQuestions 独立子问题列表（各自跑通道召回）
     * @param byLlm        true=LLM 改写成功；false=规则切分兜底（LLM 不可用/超时/输出非法）
     */
    record Result(String rewritten, List<String> subQuestions, boolean byLlm) {

        public Result {
            subQuestions = List.copyOf(subQuestions);
        }
    }
}
