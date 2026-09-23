package com.pkb.search.tokenizer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * bigram 分词器（设计文档 D2，方案 b：零新扩展实现中文关键词检索）。
 *
 * <p>规则：
 * <ul>
 *   <li>连续 CJK 字符按相邻二元切分："内存碎片" → "内存 存碎 碎片"（单个汉字原样保留）；</li>
 *   <li>ASCII 连续串（字母/数字/下划线/中划线）整词保留不切："ConcurrentHashMap" 原样；</li>
 *   <li>标点与空白是天然分隔。</li>
 * </ul>
 *
 * <p>放在应用层而不是 PG 的 generated column：换分词器只需改这一个类 + 重建索引，不用改 DDL。
 * 查询侧（KeywordSearchChannel）对每个子问题做同样切分后走 plainto_tsquery('simple', ...)，
 * AND 语义天然防过召回（一个核心词不在库里整个查询即无命中）；
 * 'simple' 而非 'english'：词干还原与英文停用词过滤对中文 bigram 无意义且会误删 ASCII 词。
 */
@Component
public class BigramTokenizer {

    /** 输出空格分隔的 token 串（写库时喂给 to_tsvector('simple', ...)） */
    public String tokenize(String text) {
        return String.join(" ", tokens(text));
    }

    public List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isAsciiWord(c)) {
                int j = i + 1;
                while (j < n && isAsciiWord(text.charAt(j))) {
                    j++;
                }
                result.add(text.substring(i, j).toLowerCase());
                i = j;
            } else if (isCjk(c)) {
                int j = i;
                while (j < n && isCjk(text.charAt(j))) {
                    j++;
                }
                String seg = text.substring(i, j);
                if (seg.length() == 1) {
                    result.add(seg);
                } else {
                    for (int k = 0; k + 1 < seg.length(); k++) {
                        result.add(seg.substring(k, k + 2));
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
        return result;
    }

    private boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '+' || c == '#';
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
