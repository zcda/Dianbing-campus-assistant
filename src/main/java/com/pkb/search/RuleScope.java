package com.pkb.search;

/** 保守地识别问题中的显式身份和校区；未识别时由回答提示适用范围。 */
public record RuleScope(String audience, String campus, String academicYear) {
    public static RuleScope from(String question) {
        String q = question == null ? "" : question;
        String audience = q.contains("本科") ? "本科生"
                : q.contains("博士") ? "博士生"
                : q.contains("学硕") || q.contains("学术硕士") ? "全日制学术硕士"
                : q.contains("专硕") || q.contains("专业硕士") ? "专业硕士"
                : q.contains("硕士") ? "硕士生"
                : q.contains("研究生") ? "研究生" : null;
        String campus = q.contains("清水河") ? "清水河校区"
                : q.contains("沙河") ? "沙河校区" : null;
        java.util.regex.Matcher year = java.util.regex.Pattern.compile("20[0-9]{2}(?:级|年)?").matcher(q);
        return new RuleScope(audience, campus, year.find() ? year.group().substring(0, 4) : null);
    }
}
