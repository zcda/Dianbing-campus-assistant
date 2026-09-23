package com.pkb.rag;

import com.pkb.search.Source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Narrow, deterministic audit for credit thresholds. It checks whether a cited snippet contains
 * the same labelled credit value; it cannot prove semantic or scope correctness.
 */
public final class NumericCitationCheck {
    private static final Pattern CREDIT_CLAIM = Pattern.compile(
            "(课程总学分|专业基础课|专业选修课|必修环节|学位课|总学分)[^，。；;\\n]{0,20}?(\\d{1,3})\\s*个?学分");
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private NumericCitationCheck() { }

    public static Result check(String answer, List<Source> sources) {
        if (answer == null || sources == null) return new Result(0, 0, List.of());
        Set<Integer> cited = new HashSet<>();
        Matcher citations = CITATION.matcher(answer);
        while (citations.find()) cited.add(Integer.parseInt(citations.group(1)));

        int total = 0;
        int supported = 0;
        List<String> unsupported = new ArrayList<>();
        Matcher claims = CREDIT_CLAIM.matcher(answer);
        while (claims.find()) {
            total++;
            String label = claims.group(1);
            String value = claims.group(2);
            boolean found = sources.stream()
                    .filter(s -> cited.contains(s.index()))
                    .anyMatch(s -> containsClaim(s.content(), label, value));
            if (found) supported++;
            else unsupported.add(label + " " + value + "学分");
        }
        return new Result(total, supported, List.copyOf(unsupported));
    }

    private static boolean containsClaim(String content, String label, String value) {
        if (content == null) return false;
        Matcher claims = CREDIT_CLAIM.matcher(content);
        while (claims.find()) {
            if (label.equals(claims.group(1)) && value.equals(claims.group(2))) return true;
        }
        return false;
    }

    public record Result(int total, int supported, List<String> unsupported) {
        public Double coverage() { return total == 0 ? null : (double) supported / total; }
    }
}
