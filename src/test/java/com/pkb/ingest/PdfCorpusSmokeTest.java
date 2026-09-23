package com.pkb.ingest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 入库前确认仓库内的真实 PDF 有文本层，扫描件不能悄悄变成空规则。 */
class PdfCorpusSmokeTest {
    @Test
    void bundledPdfsCanBeRead() throws Exception {
        TikaDocumentParser parser = new TikaDocumentParser();
        try (var paths = Files.list(Path.of("doc"))) {
            var pdfs = paths.filter(p -> p.getFileName().toString().endsWith(".pdf")).toList();
            assertFalse(pdfs.isEmpty(), "docs 中没有 PDF");
            for (Path pdf : pdfs) {
                try (var input = Files.newInputStream(pdf)) {
                    String text = parser.parse(input);
                    assertTrue(text.length() > 500, pdf + " 没有足够的可提取文本");
                    assertTrue(text.contains("电子科技大学"), pdf + " 与预期学校不符");
                }
            }
        }
    }
}
