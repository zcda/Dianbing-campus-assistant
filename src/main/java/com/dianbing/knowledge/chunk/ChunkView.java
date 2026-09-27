package com.dianbing.knowledge.chunk;

/** 笔记切片的只读视图（前端"分块"页签 + 引用定位用）。改造 v1 增加 tokenCount / level（只增不改）。 */
public record ChunkView(int seq, String content, int length, boolean hasEmbedding,
                        Integer tokenCount, String level) {
}
