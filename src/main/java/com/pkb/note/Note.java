package com.pkb.note;

import java.time.LocalDateTime;

public record Note(Long id, String title, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
