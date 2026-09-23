package com.pkb.rewrite;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则切分兜底（设计文档 D6 第 3 点，ragent 的 ruleBasedSplit 经验，必须保留的降级路径）：
 * LLM 不可用 / 超时 / 输出非法时按标点切分，每段补问号 —— 绝不抛异常，问答仍然可用，仅拆分质量下降。
 */
@Component
public class RuleBasedSplitter {
    /** Independent numeric requirements sharing one major/degree scope, e.g. C119. */
    private static final Pattern PARALLEL_NUMERIC = Pattern.compile(
            "^(.+?)(课程总学分|课程学分|总学分|学位课|实践学分|学制)(?:和|与)"
                    + "(课程总学分|课程学分|总学分|学位课|实践学分|学制)"
                    + "分别(至少多少|不低于多少|是多少|多少)[?？]?$");

    public List<String> splitParallelNumeric(String question) {
        if (question == null) return List.of();
        Matcher match = PARALLEL_NUMERIC.matcher(question.strip());
        if (!match.matches()) return List.of();
        String scope = match.group(1);
        String suffix = match.group(4) + "？";
        return List.of(scope + match.group(2) + suffix, scope + match.group(3) + suffix);
    }

    public List<String> split(String question, int maxSubQuestions) {
        List<String> parallel = splitParallelNumeric(question);
        if (!parallel.isEmpty()) return parallel.subList(0, Math.min(maxSubQuestions, parallel.size()));
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
