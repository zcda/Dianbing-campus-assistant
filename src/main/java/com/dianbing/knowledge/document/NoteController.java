package com.dianbing.knowledge.document;

import com.dianbing.knowledge.chunk.ChunkView;
import com.dianbing.knowledge.ingest.ImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    public record NoteRequest(String title, String content, RuleMetadata metadata) {
    }

    private final NoteService service;
    private final ImportService importService;

    public NoteController(NoteService service, ImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping
    public List<Note> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Note get(@PathVariable long id) {
        return service.get(id);
    }

    /** 笔记的切片明细：前端「分块」页签与引用跳转定位都靠它 */
    @GetMapping("/{id}/chunks")
    public List<ChunkView> chunks(@PathVariable long id) {
        return service.listChunks(id);
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<byte[]> original(@PathVariable long id) {
        var file = service.original(id);
        String name = file.filename() == null ? "rule" : file.filename();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.bytes());
    }

    @PostMapping
    public ResponseEntity<Note> create(@RequestBody NoteRequest request) {
        Note note = service.createRule(request.title(), request.content(), request.metadata());
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    /** 文件导入：md/txt 直读，pdf/docx 等交给 Tika 解析，导入后投递索引任务。 */
    @PostMapping("/import")
    public ResponseEntity<Note> importFile(@RequestParam("file") MultipartFile file) {
        Note note = importService.importFile(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    @PutMapping("/{id}")
    public Note update(@PathVariable long id, @RequestBody NoteRequest request) {
        return service.updateRule(id, request.title(), request.content(), request.metadata());
    }

    @PutMapping("/{id}/metadata")
    public Note updateMetadata(@PathVariable long id, @RequestBody RuleMetadata metadata) {
        return service.updateMetadata(id, metadata);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
