package com.pkb.rewrite;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则切分兜底（设计文档 D6 第 3 点，ragent 的 ruleBasedSplit 经验，必须保留的降级路径）：
 * LLM 不可用 / 超时 / 输出非法时按标点切分，每段补问号 —— 绝不抛异常，问答仍然可用，仅拆分质量下降。
 */
@Component
public class RuleBasedSplitter {

    public List<String> split(String question, int maxSubQuestions) {
        List<String> parts = new ArrayList<>();
        for (String segment : question.split("[?？。；;\\n]+")) {
            String text = segment.strip();
            if (text.isEmpty()) {
                continue;
            }
            parts.add(text);
        }
        if (parts.size() <= 1) {
            return List.of(question.strip());
        }
        // 每段补问号，保持独立问句形态
        List<String> subQuestions = new ArrayList<>();
        for (String part : parts) {
            subQuestions.add(part.endsWith("？") || part.endsWith("?") ? part : part + "？");
        }
        return subQuestions.size() <= maxSubQuestions ? subQuestions
                : new ArrayList<>(subQuestions.subList(0, maxSubQuestions));
    }
}
