package com.pkb.note;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * sourceType：manual=手工创建，import=文件导入；sourceFilename 记录原始文件名（仅导入有值）
 */
public record Note(Long id, String title, String content, String sourceType, String sourceFilename,
                   LocalDateTime createdAt, LocalDateTime updatedAt,
                   String category, String issuer, String documentNo, String sourceUrl,
                   LocalDate publishedAt, LocalDate effectiveFrom, LocalDate effectiveTo,
                   String status, String audience, String campus, String academicYear, String version,
                   boolean hasOriginal, boolean indexReady, long contentRevision) {
}
