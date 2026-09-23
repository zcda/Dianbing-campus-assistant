package com.pkb.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pkb.search.Source;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepository {

    private static final TypeReference<List<Source>> SOURCE_LIST = new TypeReference<>() {
    };

    private final JdbcClient db;
    private final ObjectMapper json;

    public ConversationRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public List<Conversation> findAll() {
        return db.sql("SELECT id, title, created_at, updated_at FROM conversation ORDER BY updated_at DESC")
                .query(this::mapConversation)
                .list();
    }

    public Optional<Conversation> findById(long id) {
        return db.sql("SELECT id, title, created_at, updated_at FROM conversation WHERE id = :id")
                .param("id", id)
                .query(this::mapConversation)
                .optional();
    }

    public Conversation insert(String title) {
        return db.sql("""
                        INSERT INTO conversation(title) VALUES (:title)
                        RETURNING id, title, created_at, updated_at
                        """)
                .param("title", title)
                .query(this::mapConversation)
                .single();
    }

    public void updateTitle(long id, String title) {
        db.sql("UPDATE conversation SET title = :title, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", id)
                .param("title", title)
                .update();
    }

    public void touch(long id) {
        db.sql("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", id)
                .update();
    }

    public boolean deleteById(long id) {
        return db.sql("DELETE FROM conversation WHERE id = :id")
                .param("id", id)
                .update() > 0;
    }

    public List<ChatMessage> findMessages(long conversationId) {
        return db.sql("""
                        SELECT id, role, content, sources::text AS sources, status, created_at
                        FROM chat_message WHERE conversation_id = :cid ORDER BY id
                        """)
                .param("cid", conversationId)
                .query(this::mapMessage)
                .list();
    }

    /** 取最近 limit 条消息，按时间正序返回（用于拼多轮上下文） */
    public List<ChatMessage> findRecentMessages(long conversationId, int limit) {
        List<ChatMessage> latest = db.sql("""
                        SELECT id, role, content, sources::text AS sources, status, created_at
                        FROM chat_message WHERE conversation_id = :cid ORDER BY id DESC LIMIT :limit
                        """)
                .param("cid", conversationId)
                .param("limit", limit)
                .query(this::mapMessage)
                .list();
        List<ChatMessage> ordered = new ArrayList<>(latest);
        Collections.reverse(ordered);
        return ordered;
    }

    public ChatMessage insertMessage(long conversationId, String role, String content, List<Source> sources) {
        return db.sql("""
                        INSERT INTO chat_message(conversation_id, role, content, sources)
                        VALUES (:cid, :role, :content, CAST(:sources AS jsonb))
                        RETURNING id, role, content, sources::text AS sources, status, created_at
                        """)
                .param("cid", conversationId)
                .param("role", role)
                .param("content", content)
                .param("sources", toJson(sources))
                .query(this::mapMessage)
                .single();
    }

    private String toJson(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(sources);
        } catch (Exception e) {
            throw new IllegalStateException("引用列表序列化失败", e);
        }
    }

    private List<Source> parseSources(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, SOURCE_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("引用列表反序列化失败", e);
        }
    }

    private Conversation mapConversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private ChatMessage mapMessage(ResultSet rs, int row) throws SQLException {
        return new ChatMessage(
                rs.getLong("id"),
                rs.getString("role"),
                rs.getString("content"),
                parseSources(rs.getString("sources")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("status"));
    }
}
