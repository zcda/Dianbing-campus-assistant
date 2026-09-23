package com.pkb.index;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcIndexTaskStore implements IndexTaskStore {

    private final JdbcClient db;

    public JdbcIndexTaskStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    @Transactional
    public boolean enqueue(long noteId, String type) {
        int inserted = db.sql("""
                        INSERT INTO index_task(note_id, type)
                        VALUES (:noteId, :type)
                        ON CONFLICT (note_id) WHERE status IN ('PENDING','RUNNING') DO NOTHING
                        """)
                .param("noteId", noteId)
                .param("type", type)
                .update();
        if (inserted > 0) {
            return true;
        }
        // 复用已有任务：若正卡在 RUNNING（保存时的内容可能晚于认领时读到的），
        // 拉回 PENDING —— worker 完成后 markSuccess 是条件更新（仅 RUNNING 可标 SUCCESS），
        // 被拉回 PENDING 的任务不会误标成功，下一轮会用最新内容重跑。
        db.sql("""
                        UPDATE index_task SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                        WHERE note_id = :noteId AND status = 'RUNNING'
                        """)
                .param("noteId", noteId)
                .update();
        return false;
    }

    @Override
    public Optional<IndexTask> claimNext() {
        return db.sql("""
                        UPDATE index_task
                        SET status = 'RUNNING', attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = (
                            SELECT id FROM index_task WHERE status = 'PENDING'
                            ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                        )
                        RETURNING id, note_id, type, status, attempts, last_error, created_at, updated_at
                        """)
                .query(this::map)
                .optional();
    }

    @Override
    public void markSuccess(long id) {
        db.sql("UPDATE index_task SET status = 'SUCCESS', last_error = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = :id AND status = 'RUNNING'")
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(long id, String error, boolean willRetry) {
        String next = willRetry ? IndexTask.PENDING : IndexTask.FAILED;
        db.sql("""
                        UPDATE index_task
                        SET status = :next, last_error = :error, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """)
                .param("next", next)
                .param("error", truncate(error, 500))
                .param("id", id)
                .update();
    }

    @Override
    @Transactional
    public int enqueueRebuildAll() {
        // 换向量模型 / 回填 tsv 的全量重建：清空 embedding 缓存，全部笔记重新投递
        // 从请求提交起暂停旧切片检索，避免重建期间继续引用旧向量或旧正文。
        db.sql("UPDATE note SET index_ready = FALSE").update();
        db.sql("TRUNCATE TABLE embedding_cache").update();
        List<Long> noteIds = db.sql("SELECT id FROM note ORDER BY id")
                .query(Long.class)
                .list();
        for (Long noteId : noteIds) {
            enqueue(noteId, IndexTask.REBUILD);
        }
        return noteIds.size();
    }

    @Override
    public List<IndexTask> list(String status, int limit) {
        String sql = """
                SELECT id, note_id, type, status, attempts, last_error, created_at, updated_at
                FROM index_task
                """;
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = :status";
        }
        sql += " ORDER BY id DESC LIMIT :limit";
        JdbcClient.StatementSpec spec = db.sql(sql)
                .param("limit", limit);
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status.toUpperCase());
        }
        return spec.query(this::map).list();
    }

    private IndexTask map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new IndexTask(
                rs.getLong("id"),
                rs.getLong("note_id"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getObject("updated_at", java.time.LocalDateTime.class));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
