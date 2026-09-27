package com.dianbing.knowledge.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 文档解析抽象：导入管道只依赖这个接口，新增格式只需加一个实现。
 */
public interface DocumentParser {

    /** 支持的扩展名（小写，不含点） */
    Set<String> extensions();

    /** 解析为 Markdown / 纯文本 */
    String parse(InputStream in) throws IOException;

    /**
     * 抽出的文本是否需要按"二进制文档"清洗（去页码与重复页眉页脚、合并被硬换行切断的段落）。
     * Markdown 原文不需要，PDF/Word 这类带排版信息的格式需要。
     */
    default boolean needsLayoutCleanup() {
        return false;
    }
}