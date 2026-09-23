package com.pkb.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pkb.config.RagProperties;
import com.pkb.llm.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 查询改写器（设计文档 D6）：
 * 归一化后的问句 → 结合历史解决指代 → 改写为检索友好的独立问句，复合问题拆成多个子问题。
 *
 * <p>规则预判（R4 降级，实测触发）：本地 8B 模型生成改写 JSON 需要 10~20s，
 * 给所有问句无差别改写会显著恶化 TTFT —— 只有复合问句（并列连词/多问号）才值得付改写成本
 * （复合问题不拆则单次检索必然漏一半，F4）；简单问句直接原句检索，零改写延迟。
 *
 * <p>降级契约：LLM 不可用 / 超时（timeout-ms）/ 输出非法 JSON → 规则切分兜底，绝不抛异常。
 * 调用参数 temperature=0.1、topP=0.3 —— 改写要稳定不要发散。
 */
@Component
public class LlmQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    /** 复合问句信号：多个问号、并列连词、顿号、分号 —— 命中其一才触发 LLM 改写 */
    private static final java.util.regex.Pattern COMPOUND_SIGNAL =
            java.util.regex.Pattern.compile("[?？].*[?？]|和|分别|以及|还有|、|；");

    private final ChatClient chatClient;
    private final RuleBasedSplitter ruleBasedSplitter;
    private final RagProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmQueryRewriter(ChatClient chatClient, RuleBasedSplitter ruleBasedSplitter, RagProperties props) {
        this.chatClient = chatClient;
        this.ruleBasedSplitter = ruleBasedSplitter;
        this.props = props;
    }

    @Override
    public Result rewrite(String question, List<String> historyLines) {
        RagProperties.Rewrite cfg = props.getRewrite();
        String normalized = question.strip();
        if (!cfg.isEnabled() || normalized.isEmpty()) {
            return new Result(normalized, List.of(normalized), false);
        }
        // R4 规则预判：简单问句跳过 LLM 改写，避免无差别的 10s+ 延迟
        if (!COMPOUND_SIGNAL.matcher(normalized).find()) {
            return new Result(normalized, List.of(normalized), false);
        }
        try {
            String raw = chatClient.complete(null, systemPrompt(cfg), userPrompt(normalized, historyLines),
                    cfg.getTemperature(), cfg.getTopP(), cfg.getTimeoutMs());
            Result parsed = parse(raw, normalized, cfg.getMaxSubQuestions());
            if (parsed != null) {
                return parsed;
            }
            log.warn("改写输出无法解析（走规则切分兜底）: {}", raw.strip().replaceAll("\\s+", " "));
        } catch (Exception e) {
            log.warn("LLM 改写失败（走规则切分兜底，问答不受影响）: {}", e.toString());
        }
        List<String> fallback = ruleBasedSplitter.split(normalized, cfg.getMaxSubQuestions());
        return new Result(fallback.get(0), fallback, false);
    }

    /** 容错解析：剥离 Markdown 代码块围栏后 parse；字段缺失/非法视为失败返回 null */
    private Result parse(String raw, String original, int maxSubQuestions) {
        try {
            String text = raw.strip();
            if (text.startsWith("```")) {
                int first = text.indexOf('\n');
                int last = text.lastIndexOf("```");
                if (first >= 0 && last > first) {
                    text = text.substring(first + 1, last).strip();
                }
            }
            int braceStart = text.indexOf('{');
            int braceEnd = text.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                text = text.substring(braceStart, braceEnd + 1);
            }
            JsonNode root = mapper.readTree(text);
            JsonNode rewriteNode = root.get("rewrite");
            JsonNode subsNode = root.get("sub_questions");
            String rewritten = rewriteNode != null && rewriteNode.isTextual()
                    ? rewriteNode.asText().strip() : null;
            List<String> subs = new ArrayList<>();
            if (subsNode != null && subsNode.isArray()) {
                for (JsonNode item : subsNode) {
                    String sub = item.isTextual() ? item.asText().strip() : "";
                    if (!sub.isEmpty() && !subs.contains(sub)) {
                        subs.add(sub);
                    }
                }
            }
            if (rewritten == null || rewritten.isEmpty() || subs.isEmpty()) {
                return null;
            }
            if (subs.size() > maxSubQuestions) {
                subs = new ArrayList<>(subs.subList(0, maxSubQuestions));
            }
            return new Result(rewritten, subs, true);
        } catch (Exception e) {
            return null;
        }
    }

    private String systemPrompt(RagProperties.Rewrite cfg) {
        return """
                你是知识库检索的查询改写器。请把用户问题改写为适合检索的独立问句，复合问题拆成多个独立子问题。
                要求：
                1. 结合【历史对话】理解代词和指代关系，改写后的问句必须指代明确、可直接独立检索；
                2. 只做改写与拆分，不回答问题，不添加问题中没有的信息；
                3. 输出严格 JSON（不要 Markdown 围栏、不要多余文字）：{"rewrite": "改写后的主问句", "sub_questions": ["子问题1", "子问题2"]}；
                4. sub_questions 最多 %d 条；单一问题输出一个子问题即可。""".formatted(cfg.getMaxSubQuestions());
    }

    private String userPrompt(String question, List<String> historyLines) {
        StringBuilder sb = new StringBuilder();
        if (historyLines != null && !historyLines.isEmpty()) {
            sb.append("【历史对话】（仅用于理解指代）\n");
            for (String line : historyLines) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【问题】\n").append(question);
        return sb.toString();
    }
}
