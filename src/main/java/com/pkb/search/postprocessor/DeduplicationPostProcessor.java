package com.pkb.search.postprocessor;

import com.pkb.search.RetrievedChunk;
import com.pkb.search.channel.SearchChannelResult;
import com.pkb.search.channel.SearchContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 去重处理器（order=1）：同一 (noteId, seq) 被多通道命中时合并为一条 —— 分数取各自非空值、
 * 通道取并集（"多路命中"信息保留在 hitChannels，供归因与加成）。
 */
@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() { return "Dedup"; }

    @Override
    public int getOrder() { return 1; }

    @Override
    public boolean isEnabled(SearchContext ctx) { return true; }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> candidates,
                                        List<SearchChannelResult> channelResults,
                                        SearchContext ctx) {
        Map<String, RetrievedChunk> unique = new LinkedHashMap<>();
        for (RetrievedChunk chunk : candidates) {
            RetrievedChunk existing = unique.get(chunk.ref());
            if (existing == null) {
                unique.put(chunk.ref(), chunk);
            } else {
                existing.mergeFrom(chunk);
            }
        }
        return new ArrayList<>(unique.values());
    }
}
