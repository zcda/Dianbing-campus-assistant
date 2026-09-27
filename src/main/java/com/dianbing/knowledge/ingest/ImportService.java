package com.dianbing.knowledge.ingest;

import com.dianbing.knowledge.document.Note;
import com.dianbing.knowledge.document.NoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 文件导入：解析成文本 → 归一化 → 落库成笔记 → 复用现有切片/向量化管道。
 * 只负责"把文件变成笔记正文"，索引逻辑完全不重复实现。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int MAX_TITLE_LENGTH = 120;
    private static final String DEFAULT_TITLE = "导入文档";

    private final List<DocumentParser> parsers;
    private final TextCleaner cleaner;
    private final NoteService notes;

    public ImportService(List<DocumentParser> parsers, TextCleaner cleaner, NoteService notes) {
        this.parsers = parsers;
        this.cleaner = cleaner;
        this.notes = notes;
    }

    public Note importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().strip();
        try {
            return importBytes(filename, file.getBytes(), file.getContentType());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取文件失败：" + e.getMessage());
        }
    }

    /** 仓库自带语料入口；调用者负责校验路径与 SHA-256。 */
    public Note importLocalFile(Path path) throws IOException {
        return importBytes(path.getFileName().toString(), Files.readAllBytes(path), "application/pdf");
    }

    private Note importBytes(String filename, byte[] original, String contentType) {
        if (original.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        String extension = extensionOf(filename);
        DocumentParser parser = parsers.stream()
                .filter(candidate -> candidate.extensions().contains(extension))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "不支持的文件类型：" + (extension.isEmpty() ? filename : "." + extension)
                                + "，当前支持 " + supportedExtensions()));

        String raw;
        try (InputStream in = new ByteArrayInputStream(original)) {
            raw = parser.parse(in);
        } catch (IOException e) {
            log.warn("解析文件[{}]失败: {}", filename, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "解析文件失败：" + e.getMessage());
        }

        String content = cleaner.clean(raw, parser.needsLayoutCleanup());
        if (content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未能从文件中提取到文本（扫描版 PDF 无文本层，需要 OCR，暂不支持）");
        }

        Note note = notes.createImported(titleOf(filename), content, filename, original, contentType);
        log.info("导入文件[{}]完成，笔记 id={}，索引已投递", filename, note.id());
        return note;
    }

    private String supportedExtensions() {
        Set<String> all = new TreeSet<>();
        parsers.forEach(parser -> all.addAll(parser.extensions()));
        return String.join(" / ", all);
    }

    private String extensionOf(String filename) {
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        return dot <= 0 ? "" : base.substring(dot + 1).toLowerCase();
    }

    /** 用文件名（去掉扩展名）作为笔记标题，导入后可在编辑器里改 */
    private String titleOf(String filename) {
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.strip();
        if (base.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return base.length() > MAX_TITLE_LENGTH ? base.substring(0, MAX_TITLE_LENGTH) : base;
    }
}
