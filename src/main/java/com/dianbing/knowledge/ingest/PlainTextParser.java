package com.dianbing.knowledge.ingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * md/txt 直接按 UTF-8 读取。
 * 不走 Tika 是因为 Tika 的文本解析会重排段落、丢掉 Markdown 标记，
 * 而切片策略（ChunkService.splitByHeading）正是依赖 ## 结构做分节。
 */
@Component
public class PlainTextParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of("md", "markdown", "txt", "text");

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public String parse(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}