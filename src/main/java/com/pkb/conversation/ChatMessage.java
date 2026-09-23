package com.pkb.conversation;

import com.pkb.search.Source;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话中的一条消息。role 为 user / assistant；
 * assistant 消息的 sources 记录当时的引用列表（JSONB），便于刷新后仍能点回原分块。
 * 改造 v1（设计文档 D6）：新增 status —— NORMAL | INTERRUPTED | RATE_LIMITED，
 * 改写历史窗口里半截消息不占名额；旧数据反序列化时归一为 NORMAL。
 */
public record ChatMessage(Long id, String role, String content, List<Source> sources,
                          LocalDateTime createdAt, String status) {

    public ChatMessage {
        sources = sources == null ? List.of() : sources;
        status = status == null || status.isBlank() ? "NORMAL" : status;
    }

    /** 兼容旧调用方的 5 参构造 */
    public ChatMessage(Long id, String role, String content, List<Source> sources, LocalDateTime createdAt) {
        this(id, role, content, sources, createdAt, "NORMAL");
    }

    public boolean normal() {
        return "NORMAL".equals(status);
    }
}
