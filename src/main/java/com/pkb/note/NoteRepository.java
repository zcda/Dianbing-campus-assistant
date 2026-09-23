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

    private static final String COLUMNS = "id, title, content, source_type, source_filename, created_at, updated_at, "
            + "category, issuer, document_no, source_url, published_at, effective_from, effective_to, "
            + "status, audience, campus, academic_year, version, index_ready, content_revision, "
            + "(original_file IS NOT NULL) AS has_original";

    private final JdbcClient db;

    public NoteRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Note> findAll() {
        return db.sql("SELECT " + COLUMNS + " FROM note ORDER BY updated_at DESC")
                .query(this::mapNote)
                .list();
    }

    public Optional<Note> findById(long id) {
        return db.sql("SELECT " + COLUMNS + " FROM note WHERE id = :id")
                .param("id", id)
                .query(this::mapNote)
                .optional();
    }

    public Optional<Note> findByIdForUpdate(long id) {
        return db.sql("SELECT " + COLUMNS + " FROM note WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::mapNote)
                .optional();
    }

    public Note insert(String title, String content, String sourceType, String sourceFilename) {
        return db.sql("""
                        INSERT INTO note(title, content, source_type, source_filename)
                        VALUES (:title, :content, :sourceType, :sourceFilename)
                        RETURNING """ + " " + COLUMNS)
                .param("title", title)
                .param("content", content)
                .param("sourceType", sourceType)
                .param("sourceFilename", sourceFilename)
                .query(this::mapNote)
                .single();
    }

    public Optional<Note> update(long id, String title, String content) {
        return db.sql("""
                        UPDATE note SET title = :title, content = :content, index_ready = FALSE,
                                        content_revision = content_revision + 1,
                                        updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        RETURNING """ + " " + COLUMNS)
                .param("id", id)
                .param("title", title)
                .param("content", content)
                .query(this::mapNote)
                .optional();
    }

    public Note updateMetadata(long id, RuleMetadata m) {
        return db.sql("""
                        UPDATE note SET category=:category, issuer=:issuer, document_no=:documentNo,
                            source_url=:sourceUrl, published_at=:publishedAt,
                            effective_from=:effectiveFrom, effective_to=:effectiveTo,
                            status=:status, audience=:audience, campus=:campus,
                            academic_year=:academicYear, version=:version,
                            updated_at=CURRENT_TIMESTAMP
                        WHERE id=:id RETURNING """ + " " + COLUMNS)
                .param("id", id)
                .param("category", m.category())
                .param("issuer", m.issuer())
                .param("documentNo", m.documentNo())
                .param("sourceUrl", m.sourceUrl())
                .param("publishedAt", m.publishedAt())
                .param("effectiveFrom", m.effectiveFrom())
                .param("effectiveTo", m.effectiveTo())
                .param("status", m.status())
                .param("audience", m.audience())
                .param("campus", m.campus())
                .param("academicYear", m.academicYear())
                .param("version", m.version())
                .query(this::mapNote)
                .single();
    }

    public boolean deleteById(long id) {
        return db.sql("DELETE FROM note WHERE id = :id")
                .param("id", id)
                .update() > 0;
    }

    public void saveOriginal(long id, byte[] bytes, String contentType) {
        db.sql("UPDATE note SET original_file=:bytes, original_content_type=:contentType WHERE id=:id")
                .param("bytes", bytes).param("contentType", contentType).param("id", id).update();
    }

    public void setIndexReady(long id, long revision, boolean ready) {
        db.sql("UPDATE note SET index_ready=:ready WHERE id=:id AND content_revision=:revision")
                .param("ready", ready).param("id", id).param("revision", revision).update();
    }

    public Optional<OriginalFile> findOriginal(long id) {
        return db.sql("SELECT source_filename, original_content_type, original_file FROM note WHERE id=:id AND original_file IS NOT NULL")
                .param("id", id)
                .query((rs, row) -> new OriginalFile(rs.getString("source_filename"),
                        rs.getString("original_content_type"), rs.getBytes("original_file")))
                .optional();
    }

    public record OriginalFile(String filename, String contentType, byte[] bytes) { }

    private Note mapNote(ResultSet rs, int row) throws SQLException {
        return new Note(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("source_type"),
                rs.getString("source_filename"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("category"), rs.getString("issuer"), rs.getString("document_no"),
                rs.getString("source_url"), rs.getObject("published_at", java.time.LocalDate.class),
                rs.getObject("effective_from", java.time.LocalDate.class),
                rs.getObject("effective_to", java.time.LocalDate.class),
                rs.getString("status"), rs.getString("audience"), rs.getString("campus"),
                rs.getString("academic_year"), rs.getString("version"), rs.getBoolean("has_original"),
                rs.getBoolean("index_ready"), rs.getLong("content_revision"));
    }
}
