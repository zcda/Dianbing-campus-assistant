package com.dianbing.knowledge.ingest;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * pdf/docx/pptx 等带排版信息的格式交给 Tika 自动识别解析。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of(
            "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls",
            "html", "htm", "rtf", "odt", "epub");

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public boolean needsLayoutCleanup() {
        return true;
    }

    @Override
    public String parse(InputStream in) throws IOException {
        // writeLimit = -1 表示不限制字符数；默认 10 万字符会把长文档截断
        BodyContentHandler handler = new BodyContentHandler(-1);
        try {
            // AutoDetectParser 非线程安全，每次解析新建实例（底层格式探测有静态缓存，开销可忽略）
            new AutoDetectParser().parse(in, handler, new Metadata(), new ParseContext());
        } catch (SAXException | TikaException e) {
            throw new IOException("Tika 解析失败: " + e.getMessage(), e);
        }
        return handler.toString();
    }
}