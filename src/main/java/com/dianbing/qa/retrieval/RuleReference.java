package com.dianbing.qa.retrieval;

import java.time.LocalDate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 随每条引用一起保存，历史问答仍能看到当时采用的规则版本。 */
public record RuleReference(String category, String issuer, String documentNo, String sourceUrl,
                            LocalDate publishedAt, LocalDate effectiveFrom, LocalDate effectiveTo,
                            String audience, String campus, String academicYear, String version,
                            String articleNo) {
    private static final Pattern ARTICLE = Pattern.compile("第[一二三四五六七八九十百千零〇0-9]+条");

    public static RuleReference from(ResultSet rs) throws SQLException {
        String content = rs.getString("content");
        Matcher article = ARTICLE.matcher(content == null ? "" : content);
        String articleNo = article.find() && article.start() < 100 ? article.group() : null;
        return new RuleReference(rs.getString("category"), rs.getString("issuer"),
                rs.getString("document_no"), rs.getString("source_url"),
                rs.getObject("published_at", LocalDate.class),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                rs.getString("audience"), rs.getString("campus"),
                rs.getString("academic_year"), rs.getString("version"), articleNo);
    }
}
