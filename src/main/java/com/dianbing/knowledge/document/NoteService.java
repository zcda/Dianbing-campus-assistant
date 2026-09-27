package com.dianbing.knowledge.document;

import com.dianbing.knowledge.chunk.ChunkRepository;
import com.dianbing.knowledge.chunk.ChunkService;
import com.dianbing.knowledge.chunk.ChunkView;
import com.dianbing.infrastructure.config.RagProperties;
import com.dianbing.knowledge.index.IndexTask;
import com.dianbing.knowledge.index.IndexTaskStore;
import com.dianbing.infrastructure.llm.EmbeddingClient;
import com.dianbing.qa.retrieval.tokenizer.BigramTokenizer;
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
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;

@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);
    private static final int EMBED_BATCH_SIZE = 10;

    private final NoteRepository notes;
    private final ChunkRepository chunks;
    private final ChunkService chunkService;
    private final EmbeddingClient embeddingClient;
    private final BigramTokenizer tokenizer;
    private final IndexTaskStore indexTasks;
    private final RagProperties props;

    public NoteService(NoteRepository notes, ChunkRepository chunks,
                       ChunkService chunkService, EmbeddingClient embeddingClient,
                       BigramTokenizer tokenizer, IndexTaskStore indexTasks, RagProperties props) {
        this.notes = notes;
        this.chunks = chunks;
        this.chunkService = chunkService;
        this.embeddingClient = embeddingClient;
        this.tokenizer = tokenizer;
        this.indexTasks = indexTasks;
        this.props = props;
    }

    public List<Note> list() {
        return notes.findAll();
    }

    public Note get(long id) {
        return notes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id));
    }

    /** 笔记的切片明细，供前端查看分块与引用定位 */
    public List<ChunkView> listChunks(long id) {
        get(id);
        return chunks.findByNoteId(id);
    }

    @Transactional
    public Note create(String title, String content) {
        return create(title, content, "manual", null);
    }

    @Transactional
    public Note createRule(String title, String content, RuleMetadata metadata) {
        Note note = create(title, content, "manual", null);
        return updateMetadata(note.id(), metadata);
    }

    /** 文件导入入口：记录来源文件名，便于区分手工笔记与导入内容 */
    @Transactional
    public Note createImported(String title, String content, String sourceFilename,
                               byte[] original, String contentType) {
        Note note = create(title, content, "import", sourceFilename);
        notes.saveOriginal(note.id(), original, contentType);
        return note;
    }

    public NoteRepository.OriginalFile original(long id) {
        return notes.findOriginal(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有保存原始文件"));
    }

    private Note create(String title, String content, String sourceType, String sourceFilename) {
        requireTitle(title);
        Note note = notes.insert(title.trim(), content == null ? "" : content, sourceType, sourceFilename);
        dispatchIndex(note);
        return note;
    }

    @Transactional
    public Note update(long id, String title, String content) {
        requireTitle(title);
        notes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id));
        Note note = notes.update(id, title.trim(), content == null ? "" : content)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + id));
        dispatchIndex(note);
        return note;
    }

    @Transactional
    public Note updateRule(long id, String title, String content, RuleMetadata metadata) {
        RuleMetadata checked = metadata == null ? null : validateMetadata(metadata);
        Note updated = update(id, title, content);
        return checked == null ? updated : notes.updateMetadata(id, checked);
    }

    @Transactional
    public Note updateMetadata(long id, RuleMetadata metadata) {
        get(id);
        RuleMetadata checked = validateMetadata(metadata);
        return notes.updateMetadata(id, checked);
    }

    private RuleMetadata validateMetadata(RuleMetadata m) {
        if (m == null) return RuleMetadata.draft();
        String status = m.status() == null || m.status().isBlank() ? "draft" : m.status().strip().toLowerCase();
        if (!Set.of("draft", "active", "expired", "revoked").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则状态不合法");
        }
        if (m.effectiveFrom() != null && m.effectiveTo() != null && m.effectiveTo().isBefore(m.effectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效日期不能早于生效日期");
        }
        if ("active".equals(status)) {
            if (blank(m.issuer()) || blank(m.sourceUrl()) ||
                    (m.effectiveFrom() == null && blank(m.academicYear()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "启用规则前须填写发布部门、官方来源链接，以及生效日期或适用学年");
            }
            try {
                URI uri = URI.create(m.sourceUrl().strip());
                String host = uri.getHost();
                String domain = props.getOfficialDomain();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
                        blank(domain) || !(host.equalsIgnoreCase(domain) ||
                        host.toLowerCase().endsWith("." + domain.toLowerCase()))) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "官方来源链接须为 " + props.getOfficialDomain() + " 的有效 HTTPS 地址");
            }
            if ((m.effectiveFrom() != null && m.effectiveFrom().isAfter(LocalDate.now())) ||
                    (m.effectiveTo() != null && m.effectiveTo().isBefore(LocalDate.now()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前日期不在规则有效期内");
            }
        }
        return new RuleMetadata(m.category(), m.issuer(), m.documentNo(), m.sourceUrl(),
                m.publishedAt(), m.effectiveFrom(), m.effectiveTo(), status,
                m.audience(), m.campus(), m.academicYear(), m.version());
    }

    private boolean blank(String text) { return text == null || text.isBlank(); }

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
     * 索引投递（设计文档 D9）：异步开启时保存路径只写 note + 投递 index_task，同步路径只做轻量校验；
     * 关闭时保持改造前的同步索引行为。异步路径的可靠性由 worker 的重试 + 幂等保证：
     * Embedding 挂了会重试自愈，而不是留下永不建索引的笔记（F8 的解药）。
     */
    private void dispatchIndex(Note note) {
        if (props.getIndex().isAsyncEnabled()) {
            boolean fresh = indexTasks.enqueue(note.id(), IndexTask.UPSERT);
            log.debug("笔记[{}]索引任务{}（noteId={}）", note.id(), fresh ? "已投递" : "复用未完成任务", note.id());
        } else {
            reindex(note);
        }
    }

    /**
     * 按笔记整体替换切片：先删旧切片，再重新切片入库，无残留脏切片（PRD 验收 3）。
     * 改造 v1：写入侧同步生成 tsv（bigram + tsvector，D2 关键词通道的写入端）；
     * 异步模式下由 IndexTaskWorker 调用（Embedding 失败 → 任务重试，而不是切片无向量裸奔）。
     */
    @Transactional
    public void reindex(Note note) {
        // 与正文更新串行化：旧任务不能在新版本提交后删掉它的切片。
        Note current = notes.findByIdForUpdate(note.id()).orElse(null);
        if (current == null || current.contentRevision() != note.contentRevision()) return;
        notes.setIndexReady(note.id(), note.contentRevision(), false);
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
            // 异步路径：异常向上抛，由 worker 重试；同步路径：向上抛会让保存失败，
            // 沿用 早期原型 的取舍——同步模式下降级为"无向量落库，重新保存可重建"
            if (props.getIndex().isAsyncEnabled()) {
                throw new IllegalStateException("向量化失败: " + e.getMessage(), e);
            }
            log.warn("笔记[{}]向量化失败，切片将以无向量形式保存（重新保存可重建索引）: {}",
                    note.id(), e.getMessage());
            embeddings.clear();
        }

        int seq = 0;
        for (String piece : pieces) {
            List<String> tokens = tokenizer.tokens(piece);
            chunks.insert(note.id(), seq++, piece, sha256(piece), embeddings.get(sha256(piece)),
                    String.join(" ", tokens), tokens.size());
        }
        notes.setIndexReady(note.id(), note.contentRevision(), embeddings.size() == hashToContent.size());
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
