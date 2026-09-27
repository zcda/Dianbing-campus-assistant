package com.dianbing.knowledge.index;

import java.util.List;
import java.util.Optional;

/**
 * 异步索引任务存储扩展点（设计文档 D9）：
 * 单用户单机单库，"任务持久化 + 重试 + 幂等"用一张表 + 一个执行器就能满足；
 * 引入 MQ 会多一个部署依赖和一段最终一致性窗口，收益是负的。
 * 保留任务表这个可观测、可重放的中间态，将来要换 MQ 只需替换本接口的实现。
 */
public interface IndexTaskStore {

    /**
     * 投递任务（幂等靠部分唯一索引 uq_index_task_active，不靠代码判断）：
     * 同一笔记已有未完成任务时 INSERT 冲突 → DO NOTHING；若已有任务在 RUNNING 中，
     * 将其改回 PENDING，保证"最后一次保存的内容最终会被索引一次"。
     *
     * @return true=新投递；false=复用已有任务
     */
    boolean enqueue(long noteId, String type);

    /** 认领一个 PENDING 任务（SKIP LOCKED，多 worker 安全），状态置 RUNNING、attempts+1 */
    Optional<IndexTask> claimNext();

    /** 任务成功（条件更新：仅 RUNNING 状态可标记，防止与投递回写竞态） */
    void markSuccess(long id);

    /** 任务失败：willRetry=true 回 PENDING（下一轮轮询重试），否则置 FAILED 并保留 last_error */
    void markFailed(long id, String error, boolean willRetry);

    /** 全量重建：清空 embedding 缓存并为全部笔记投递 rebuild 任务（换向量模型必须走这条路） */
    int enqueueRebuildAll();

    /** 任务列表（最近优先，调试与验收用） */
    List<IndexTask> list(String status, int limit);
}
