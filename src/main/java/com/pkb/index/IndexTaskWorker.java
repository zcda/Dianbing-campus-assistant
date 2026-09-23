package com.pkb.index;

import com.pkb.config.RagProperties;
import com.pkb.note.Note;
import com.pkb.note.NoteRepository;
import com.pkb.note.NoteService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 索引任务执行器（设计文档 D9）：单线程（可配）轮询消费 index_task。
 *
 * <p>可靠性语义（对照 MVP-0 的 F8「失败只打日志」）：
 * <ul>
 *   <li>Embedding 服务挂掉 → 保存笔记仍成功，任务进入重试，服务恢复后自动建好索引（自愈）；</li>
 *   <li>重试上限 attempts < rag.index.max-retry，超限置 FAILED 并保留 last_error（GET /api/index/tasks 可见）；</li>
 *   <li>幂等靠部分唯一索引：同一笔记连续保存多次，未完成任务数恒为 1。</li>
 * </ul>
 */
@Component
public class IndexTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexTaskWorker.class);

    private final IndexTaskStore store;
    private final NoteRepository notes;
    private final NoteService noteService;
    private final RagProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public IndexTaskWorker(IndexTaskStore store, NoteRepository notes,
                           NoteService noteService, RagProperties props) {
        this.store = store;
        this.notes = notes;
        this.noteService = noteService;
        this.props = props;
    }

    @PostConstruct
    void start() {
        if (!props.getIndex().isAsyncEnabled()) {
            log.info("异步索引未启用（rag.index.async-enabled=false），保存笔记走同步索引");
            return;
        }
        int threads = Math.max(1, props.getIndex().getWorkerThreads());
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "index-worker");
            t.setDaemon(true);
            return t;
        });
        running.set(true);
        for (int i = 0; i < threads; i++) {
            executor.submit(this::loop);
        }
        log.info("异步索引已启动：worker={} maxRetry={} pollInterval={}ms",
                threads, props.getIndex().getMaxRetry(), props.getIndex().getRetryBackoffMs());
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void loop() {
        long pollMs = Math.max(200, props.getIndex().getRetryBackoffMs());
        while (running.get()) {
            try {
                Optional<IndexTask> claimed = store.claimNext();
                if (claimed.isEmpty()) {
                    Thread.sleep(pollMs);
                    continue;
                }
                IndexTask task = claimed.get();
                Optional<Note> note = notes.findById(task.noteId());
                if (note.isEmpty()) {
                    // 笔记已删除：任务直接成功（chunk 已被外键级联清理）
                    store.markSuccess(task.id());
                    continue;
                }
                try {
                    Note current = note.get();
                    for (int attempt = 0; attempt < 5; attempt++) {
                        noteService.reindex(current);
                        current = notes.findById(task.noteId()).orElse(null);
                        if (current == null || current.indexReady()) break;
                    }
                    if (current != null && !current.indexReady()
                            && current.content() != null && !current.content().isBlank()) {
                        throw new IllegalStateException("索引期间规则持续更新，稍后重试");
                    }
                    store.markSuccess(task.id());
                    log.info("索引任务#{} 笔记[{}]完成（attempts={}）", task.id(), task.noteId(), task.attempts());
                } catch (Exception e) {
                    boolean willRetry = task.attempts() < props.getIndex().getMaxRetry();
                    store.markFailed(task.id(), e.toString(), willRetry);
                    if (willRetry) {
                        log.warn("索引任务#{} 笔记[{}]失败（第 {} 次），将重试: {}",
                                task.id(), task.noteId(), task.attempts(), e.toString());
                        Thread.sleep(props.getIndex().getRetryBackoffMs());
                    } else {
                        log.error("索引任务#{} 笔记[{}]重试超限置 FAILED: {}",
                                task.id(), task.noteId(), e.toString());
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("索引轮询异常: {}", e.toString());
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
