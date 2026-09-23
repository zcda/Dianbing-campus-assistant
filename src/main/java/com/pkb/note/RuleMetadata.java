package com.pkb.note;

import java.time.LocalDate;

/** 管理员确认的规则来源与适用范围。新建文档默认草稿，不进入问答。 */
public record RuleMetadata(String category, String issuer, String documentNo, String sourceUrl,
                           LocalDate publishedAt, LocalDate effectiveFrom, LocalDate effectiveTo,
                           String status, String audience, String campus, String academicYear, String version) {
    public static RuleMetadata draft() {
        return new RuleMetadata(null, null, null, null, null, null, null,
                "draft", null, null, null, null);
    }
}
