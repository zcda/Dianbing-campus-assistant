package com.pkb.note;

import com.pkb.chunk.ChunkRepository;
import com.pkb.chunk.ChunkService;
import com.pkb.llm.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);
    private static final int EMBED_BATCH_SIZE = 10;

    private final NoteRepository notes;
    private final ChunkRepository chunks;
    private final ChunkService chunkService;
    private final EmbeddingClient embeddingClient;

    public NoteService(NoteRepository notes, ChunkRepository chunks,
                       ChunkService chunkService, EmbeddingClient embeddingClient) {
        this.notes = notes;
        this.chunks = chunks;
        this.chunkService = chunkService;
        this.embeddingClient = embeddingClient;
    }

    public List<Note> list() {
        return notes.findAll();
    }

    public Note get(long id) {
        return notes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id));
    }

    @Transactional
    public Note create(String title, String content) {
        requireTitle(title);
        Note note = notes.insert(title.trim(), content == null ? "" : content);
        reindex(note);
        return note;
    }

    @Transactional
    public Note update(long id, String title, String content) {
        requireTitle(title);
        notes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id));
        Note note = notes.update(id, title.trim(), content == null ? "" : content)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id));
        reindex(note);
        return note;
    }

    @Transactional
    public void delete(long id) {
        if (!notes.deleteById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id);
        }
        // chunk 通过外键 ON DELETE CASCADE 级联删除
    }

    private void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
    }

    /**
     * 按笔记整体替换切片：先删旧切片，再重新切片入库，无残留脏切片（PRD 验收 3）。
     * Embedding 失败不阻塞笔记保存：切片以无向量形式落库，重新保存即可重建索引（PRD 非功能-可靠性）。
     */
    private void reindex(Note note) {
        chunks.deleteByNoteId(note.id());
        List<String> pieces = chunkService.split(note.content());
        if (pieces.isEmpty()) {
            return;
        }

        Map<String, String> hashToContent = new LinkedHashMap<>();
        for (String piece : pieces) {
            hashToContent.putIfAbsent(sha256(piece), piece);
        }

        Map<String, float[]> embeddings = new HashMap<>();
        try {
            embeddings.putAll(chunks.loadCached(hashToContent.keySet()));
            List<String> missing = hashToContent.keySet().stream()
                    .filter(h -> !embeddings.containsKey(h))
                    .toList();
            for (int i = 0; i < missing.size(); i += EMBED_BATCH_SIZE) {
                List<String> batch = missing.subList(i, Math.min(i + EMBED_BATCH_SIZE, missing.size()));
                List<String> texts = batch.stream().map(hashToContent::get).toList();
                List<float[]> vectors = embeddingClient.embedBatch(texts);
                for (int j = 0; j < batch.size(); j++) {
                    embeddings.put(batch.get(j), vectors.get(j));
                    chunks.saveCache(batch.get(j), vectors.get(j));
                }
            }
        } catch (Exception e) {
            log.warn("笔记[{}]向量化失败，切片将以无向量形式保存（重新保存可重建索引）: {}", note.id(), e.getMessage());
            embeddings.clear();
        }

        int seq = 0;
        for (String piece : pieces) {
            chunks.insert(note.id(), seq++, piece, sha256(piece), embeddings.get(sha256(piece)));
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
