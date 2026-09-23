package com.pkb.conversation;

import java.time.LocalDateTime;

/** 一个独立的问答会话，可并存多个、各自保留消息历史 */
public record Conversation(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
}