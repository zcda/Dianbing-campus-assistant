package com.dianbing.eval;

import com.dianbing.campus.CampusIntentPlanner;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Online semantic-routing evaluation. Enable with -Deval.intent.online=true. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "eval.intent.online", matches = "true")
class CampusIntentEvalTest {
    private static final String DATASET = "/eval/campus_intent_eval_v1.jsonl";

    @Autowired CampusIntentPlanner planner;

    @Test
    void evaluateIntentPlanning() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Sample> samples = load(mapper);
        List<Map<String, Object>> rows = new ArrayList<>();
        int correctRoute = 0;
        int correctClarification = 0;
        int correctRuleQuery = 0;
        int ruleQueryCases = 0;

        for (Sample sample : samples) {
            CampusIntentPlanner.Plan plan = planner.classify(sample.query());
            boolean routeOk = plan.mixed() == sample.expectedMixed();
            boolean clarificationOk = plan.needsClarification() == sample.expectedClarification();
            boolean ruleQueryOk = true;
            if (sample.expectedMixed() && !sample.expectedClarification()) {
                ruleQueryCases++;
                String ruleQuery = plan.ruleQuery() == null ? "" : plan.ruleQuery();
                ruleQueryOk = sample.requiredRuleTerms().stream().allMatch(ruleQuery::contains);
                if (ruleQueryOk) correctRuleQuery++;
            }
            if (routeOk) correctRoute++;
            if (clarificationOk) correctClarification++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queryId", sample.queryId());
            row.put("query", sample.query());
            row.put("expectedMixed", sample.expectedMixed());
            row.put("actualMixed", plan.mixed());
            row.put("expectedClarification", sample.expectedClarification());
            row.put("actualClarification", plan.needsClarification());
            row.put("requiredRuleTerms", sample.requiredRuleTerms());
            row.put("ruleQuery", plan.ruleQuery());
            row.put("tasks", plan.tasks());
            row.put("decision", plan.decision());
            row.put("elapsedMs", plan.elapsedMs());
            row.put("routeOk", routeOk);
            row.put("clarificationOk", clarificationOk);
            row.put("ruleQueryOk", ruleQueryOk);
            if (!routeOk) row.put("failureCategory", plan.decision().equals("candidate_guard_skip")
                    ? "CANDIDATE_GUARD_MISS" : "INTENT_CLASSIFICATION_ERROR");
            else if (!clarificationOk) row.put("failureCategory", "CLARIFICATION_ERROR");
            else if (!ruleQueryOk) row.put("failureCategory", "RULE_QUERY_CONDITION_LOSS");
            rows.add(row);
        }

        double routeAccuracy = ratio(correctRoute, samples.size());
        double clarificationAccuracy = ratio(correctClarification, samples.size());
        double ruleQueryAccuracy = ratio(correctRuleQuery, ruleQueryCases);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", DATASET);
        report.put("sampleCount", samples.size());
        report.put("routeAccuracy", routeAccuracy);
        report.put("clarificationAccuracy", clarificationAccuracy);
        report.put("ruleQueryConditionRetention", ruleQueryAccuracy);
        report.put("rows", rows);
        Path dir = Path.of("target", "eval-reports");
        Files.createDirectories(dir);
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("campus-intent-eval.json").toFile(), report);

        assertTrue(routeAccuracy >= 0.85, "路由准确率低于 85%，详见 target/eval-reports/campus-intent-eval.json");
        assertTrue(clarificationAccuracy >= 0.85, "澄清判断准确率低于 85%");
        assertTrue(ruleQueryAccuracy >= 0.85, "规则查询条件保留率低于 85%");
    }

    private List<Sample> load(ObjectMapper mapper) throws Exception {
        List<Sample> result = new ArrayList<>();
        try (var in = CampusIntentEvalTest.class.getResourceAsStream(DATASET)) {
            if (in == null) throw new IllegalStateException("找不到评测集 " + DATASET);
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) if (!line.isBlank()) result.add(mapper.readValue(line, Sample.class));
        }
        return result;
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    private record Sample(@JsonProperty("query_id") String queryId,
                          String query,
                          @JsonProperty("expected_mixed") boolean expectedMixed,
                          @JsonProperty("expected_clarification") boolean expectedClarification,
                          @JsonProperty("required_rule_terms") List<String> requiredRuleTerms) {
        Sample {
            requiredRuleTerms = requiredRuleTerms == null ? List.of() : List.copyOf(requiredRuleTerms);
        }
    }
}
