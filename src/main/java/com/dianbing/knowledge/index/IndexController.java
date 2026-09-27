package com.dianbing.knowledge.index;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 索引管理接口（设计文档 §7）：
 * <ul>
 *   <li>POST /api/index/rebuild —— 全量重建（清 embedding 缓存 + 全部笔记重新投递）。
 *       换向量模型、改分块参数、回填历史切片 tsv 都走这条路，
 *       README 里"手写 TRUNCATE embedding_cache; UPDATE chunk SET embedding = NULL;"的说明就此作废；</li>
 *   <li>GET /api/index/tasks?status= —— 任务列表（含失败原因），异步链路可观测。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final IndexTaskStore store;
    private final com.dianbing.infrastructure.config.RagProperties props;

    public IndexController(IndexTaskStore store, com.dianbing.infrastructure.config.RagProperties props) {
        this.store = store;
        this.props = props;
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        if (!props.getIndex().isAsyncEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "异步索引未启用（rag.index.async-enabled=false），请开启后重启再执行全量重建");
        }
        int enqueued = store.enqueueRebuildAll();
        return Map.of("enqueued", enqueued);
    }

    @GetMapping("/tasks")
    public List<IndexTask> tasks(@RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "100") int limit) {
        return store.list(status, Math.min(Math.max(1, limit), 500));
    }
}
