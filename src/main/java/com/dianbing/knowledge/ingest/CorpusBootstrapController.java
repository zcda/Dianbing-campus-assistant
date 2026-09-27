package com.dianbing.knowledge.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dianbing.knowledge.document.Note;
import com.dianbing.knowledge.document.NoteService;
import com.dianbing.knowledge.document.RuleMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 从当前仓库 doc 导入经人工核对的 PDF 清单。仅本地开发模式使用。 */
@RestController
@RequestMapping("/api/corpus")
public class CorpusBootstrapController {
    private final ObjectMapper json;
    private final ImportService importer;
    private final NoteService notes;

    public CorpusBootstrapController(ObjectMapper json, ImportService importer, NoteService notes) {
        this.json = json;
        this.importer = importer;
        this.notes = notes;
    }

    public record ManifestEntry(String file, String sha256, String title, RuleMetadata metadata) { }

    @PostMapping("/bootstrap")
    public List<String> bootstrap() throws IOException {
        Path root = Path.of("doc").toAbsolutePath().normalize();
        Path manifest = root.resolve("corpus-manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到 doc/corpus-manifest.json；请从仓库根目录启动");
        }
        List<ManifestEntry> entries = json.readValue(Files.readAllBytes(manifest), new TypeReference<>() { });
        // 先验证整个清单，防止中途发现文件损坏时只导入一半。
        for (ManifestEntry entry : entries) {
            Path file = checkedPath(root, entry.file());
            String actual = HexFormat.of().formatHex(digest(Files.readAllBytes(file)));
            if (!actual.equalsIgnoreCase(entry.sha256())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, entry.file() + " 的 SHA-256 与清单不符");
            }
        }
        List<String> result = new ArrayList<>();
        for (ManifestEntry entry : entries) {
            boolean exists = notes.list().stream().anyMatch(n -> entry.file().equals(n.sourceFilename()));
            if (exists) {
                result.add(entry.file() + "：已存在，跳过");
                continue;
            }
            Note imported = importer.importLocalFile(checkedPath(root, entry.file()));
            notes.updateMetadata(imported.id(), entry.metadata());
            result.add(entry.file() + "：已导入，id=" + imported.id() + "，状态=" + entry.metadata().status());
        }
        return result;
    }

    private Path checkedPath(Path root, String name) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "清单缺少文件名");
        Path resolved = root.resolve(name).normalize();
        if (!resolved.getParent().equals(root) || !Files.isRegularFile(resolved)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则文件不存在或不在 doc 根目录：" + name);
        }
        try {
            if (!resolved.toRealPath().getParent().equals(root.toRealPath())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则文件必须位于 doc 根目录：" + name);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取规则文件：" + name);
        }
        return resolved;
    }

    private byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
