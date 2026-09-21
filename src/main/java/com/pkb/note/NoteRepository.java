package com.pkb.note;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class NoteRepository {

    private final JdbcClient db;

    public NoteRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Note> findAll() {
        return db.sql("SELECT id, title, content, created_at, updated_at FROM note ORDER BY updated_at DESC")
                .query(this::mapNote)
                .list();
    }

    public Optional<Note> findById(long id) {
        return db.sql("SELECT id, title, content, created_at, updated_at FROM note WHERE id = :id")
                .param("id", id)
                .query(this::mapNote)
                .optional();
    }

    public Note insert(String title, String content) {
        return db.sql("""
                        INSERT INTO note(title, content) VALUES (:title, :content)
                        RETURNING id, title, content, created_at, updated_at
                        """)
                .param("title", title)
                .param("content", content)
                .query(this::mapNote)
                .single();
    }

    public Optional<Note> update(long id, String title, String content) {
        return db.sql("""
                        UPDATE note SET title = :title, content = :content, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        RETURNING id, title, content, created_at, updated_at
                        """)
                .param("id", id)
                .param("title", title)
                .param("content", content)
                .query(this::mapNote)
                .optional();
    }

    public boolean deleteById(long id) {
        return db.sql("DELETE FROM note WHERE id = :id")
                .param("id", id)
                .update() > 0;
    }

    private Note mapNote(ResultSet rs, int row) throws SQLException {
        return new Note(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
