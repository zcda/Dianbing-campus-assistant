package com.dianbing.qa.retrieval;

/**
 * 保守地提取用户明确说出的规则适用范围。
 *
 * <p>这些值只用于后端白名单字段过滤，不允许模型生成 SQL。没有明确范围时保持 null，
 * 避免根据会话或常识猜测用户身份。discipline 当前用于追踪和评测；数据库尚无独立专业字段，
 * 因此不会伪装成精确元数据过滤。
 */
public record RuleScope(String audience, String campus, String academicYear, String discipline) {
    public static RuleScope from(String question) {
        String q = question == null ? "" : question;
        String audience = q.contains("本科") ? "本科"
                : q.contains("博士") ? "博士"
                : q.contains("学硕") || q.contains("学术硕士") || q.contains("学术学位硕士") ? "学术硕士"
                : q.contains("专硕") || q.contains("专业硕士") ? "专业硕士"
                : q.contains("硕士") ? "硕士" : null;
        String campus = q.contains("清水河") ? "清水河校区"
                : q.contains("沙河") ? "沙河校区" : null;
        java.util.regex.Matcher year = java.util.regex.Pattern.compile("20[0-9]{2}(?:级|年)?").matcher(q);
        String discipline = discipline(q);
        return new RuleScope(audience, campus, year.find() ? year.group().substring(0, 4) : null, discipline);
    }

    public String audiencePattern() { return audience == null ? "" : "%" + audience + "%"; }
    public String campusPattern() { return campus == null ? "" : "%" + campus + "%"; }
    public String academicYearValue() { return academicYear == null ? "" : academicYear; }

    public boolean hasMetadataFilter() {
        return audience != null || campus != null || academicYear != null;
    }

    private static String discipline(String q) {
        String[] known = {"计算机科学与技术", "计算机技术", "软件工程", "数学", "新闻传播学",
                "航空宇航科学与技术", "航空宇航", "生物医学工程", "外国语言文学"};
        for (String item : known) if (q.contains(item)) return item;
        return null;
    }
}
