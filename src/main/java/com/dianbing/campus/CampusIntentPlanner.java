package com.dianbing.campus;

import com.dianbing.infrastructure.config.CampusAgentProperties;
import com.dianbing.infrastructure.llm.ChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** Uses an LLM to detect compound campus-personal + public-rule requests. */
@Component
public class CampusIntentPlanner {
    private static final Logger log = LoggerFactory.getLogger(CampusIntentPlanner.class);
    private static final Set<String> ALLOWED_TASKS = Set.of("personal_credit_summary", "rule_requirement_lookup");
    private static final String SYSTEM = """
            你是一个校园问答任务分类器，只负责识别任务，不回答问题，也不调用工具。
            只允许输出严格 JSON：{"tasks":["personal_credit_summary","rule_requirement_lookup"],"rule_query":"...","needs_clarification":false,"clarification":""}。
            personal_credit_summary 表示查询个人已修学分或学分缺口；rule_requirement_lookup 表示查询学校培养/毕业规则。
            只有原问题同时要求以上两类任务时才返回两个 task。只做个人查询、只问规则或无关问题都返回 tasks=[]。
            rule_query 必须忠实保留原问题中的专业、学位类型、年级等条件；不得自行补充用户没有说的信息。
            如果复合问题明确缺少会改变适用规则的关键信息，设置 needs_clarification=true 并用简短中文询问；否则为 false。
            对“是否满足、还差多少”等问题，应同时返回两个 task，由后续流程联合个人数据和规则依据作出判断。
            """;

    private final ChatClient chat;
    private final ObjectMapper mapper;
    private final CampusAgentProperties properties;

    public CampusIntentPlanner(ChatClient chat, ObjectMapper mapper, CampusAgentProperties properties) {
        this.chat = chat;
        this.mapper = mapper;
        this.properties = properties;
    }

    public record Plan(boolean mixed, String ruleQuery, boolean needsClarification, String clarification,
                       Set<String> tasks, String decision, long elapsedMs) {
        public Plan {
            tasks = Set.copyOf(tasks);
        }
    }

    /** A broad domain guard avoids spending a model round on ordinary RAG questions. */
    public boolean isCandidate(String question) {
        if (question == null || question.isBlank() || question.length() > 1200) return false;
        boolean personalCue = question.matches(".*(我|本人|自己|当前|已修|修了|拿了|还差|差多少).*" );
        boolean ruleCue = question.matches(".*(毕业|培养|学位|学分要求|毕业条件|毕业资格|是否满足|能否满足|符合|还差|差多少|修满|规则).*" );
        return personalCue && ruleCue;
    }

    public Plan classify(String question) {
        long start = System.nanoTime();
        if (!isCandidate(question)) return plan(false, null, false, "", Set.of(), "candidate_guard_skip", start);
        try {
            String raw = chat.complete(null, SYSTEM, question, 0.0, 0.1,
                    properties.getPlannerTimeoutMs());
            JsonNode root = mapper.readTree(stripFence(raw));
            JsonNode taskNode = root.path("tasks");
            if (!taskNode.isArray() || taskNode.size() > ALLOWED_TASKS.size()
                    || !root.path("needs_clarification").isBoolean()
                    || !root.path("rule_query").isTextual()
                    || !root.path("clarification").isTextual()) return invalid(start);
            Set<String> tasks = new LinkedHashSet<>();
            for (JsonNode item : taskNode) {
                if (!item.isTextual() || !ALLOWED_TASKS.contains(item.asText()) || !tasks.add(item.asText())) {
                    return invalid(start);
                }
            }
            boolean mixed = tasks.containsAll(ALLOWED_TASKS);
            if (!mixed) return plan(false, null, false, "", tasks, "single_or_unrelated", start);
            boolean needsClarification = root.path("needs_clarification").asBoolean(false);
            String clarification = root.path("clarification").asText("").strip();
            String ruleQuery = root.path("rule_query").asText("").strip();
            if (needsClarification) {
                if (clarification.isBlank() || clarification.length() > 200) return invalid(start);
                return plan(true, null, true, clarification, tasks, "needs_clarification", start);
            }
            if (ruleQuery.isBlank() || ruleQuery.length() > 300) return invalid(start);
            return plan(true, ruleQuery, false, "", tasks, "mixed_plan", start);
        } catch (Exception ex) {
            log.debug("校园复合意图识别失败，回退到已有路由: {}", ex.toString());
            return plan(false, null, false, "", Set.of(), "planner_error", start);
        }
    }

    private Plan invalid(long start) {
        return plan(false, null, false, "", Set.of(), "invalid_output", start);
    }

    private Plan plan(boolean mixed, String ruleQuery, boolean clarificationNeeded, String clarification,
                      Set<String> tasks, String decision, long start) {
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new Plan(mixed, ruleQuery, clarificationNeeded, clarification, tasks, decision, elapsedMs);
    }

    private String stripFence(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first >= 0 && last > first) text = text.substring(first + 1, last).strip();
        }
        return text;
    }
}
