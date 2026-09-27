package com.dianbing.knowledge.index;

import java.time.LocalDateTime;

/** 异步索引任务（index_task 表）。状态机：PENDING → RUNNING → SUCCESS / FAILED（失败重试回 PENDING）。 */
public record IndexTask(long id, long noteId, String type, String status,
                        int attempts, String lastError,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static final String UPSERT = "upsert";
    public static final String REBUILD = "rebuild";
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
