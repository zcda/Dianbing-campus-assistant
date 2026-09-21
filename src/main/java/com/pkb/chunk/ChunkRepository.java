package com.pkb.chunk;

import com.pkb.llm.VectorCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ChunkRepository {

    private final JdbcClient db;

    public ChunkRepository(JdbcClient db) {
        this.db = db;
    }

    public void deleteByNoteId(long noteId) {
        db.sql("DELETE FROM chunk WHERE note_id = :noteId")
                .param("noteId", noteId)
                .update();
    }

    /** embedding 允许为 null（Embedding 服务不可用时切片仍然落库，重新保存即可重建索引） */
    public void insert(long noteId, int seq, String content, String contentHash, float[] embedding) {
        db.sql("""
                        INSERT INTO chunk(note_id, seq, content, content_hash, embedding)
                        VALUES (:noteId, :seq, :content, :contentHash, CAST(:embedding AS vector))
                        """)
                .param("noteId", noteId)
                .param("seq", seq)
                .param("content", content)
                .param("contentHash", contentHash)
                .param("embedding", embedding == null ? null : VectorCodec.toLiteral(embedding))
                .update();
    }

    /** 按内容哈希查询已缓存的向量，避免重复调用 Embedding API */
    public Map<String, float[]> loadCached(Collection<String> hashes) {
        if (hashes.isEmpty()) {
            return Map.of();
        }
        List<String> list = List.copyOf(hashes);
        StringBuilder sql = new StringBuilder(
                "SELECT content_hash, embedding::text AS embedding FROM embedding_cache WHERE content_hash IN (");
        for (int i = 0; i < list.size(); i++) {
            sql.append(i == 0 ? ":h0" : ", :h" + i);
        }
        sql.append(")");
        JdbcClient.StatementSpec spec = db.sql(sql.toString());
        for (int i = 0; i < list.size(); i++) {
            spec = spec.param("h" + i, list.get(i));
        }
        Map<String, float[]> result = new HashMap<>();
        spec.query((rs, i) -> Map.entry(rs.getString("content_hash"), VectorCodec.parse(rs.getString("embedding"))))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public void saveCache(String contentHash, float[] embedding) {
        db.sql("""
                        INSERT INTO embedding_cache(content_hash, embedding)
                        VALUES (:contentHash, CAST(:embedding AS vector))
                        ON CONFLICT (content_hash) DO NOTHING
                        """)
                .param("contentHash", contentHash)
                .param("embedding", VectorCodec.toLiteral(embedding))
                .update();
    }
}
