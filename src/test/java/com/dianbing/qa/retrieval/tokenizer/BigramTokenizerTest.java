package com.dianbing.qa.retrieval.tokenizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * bigram 分词器纯逻辑测试（设计文档 D2）：
 * 中文按相邻二元切、ASCII 驼峰词整词保留、标点分隔、大小写归一。
 */
class BigramTokenizerTest {

    private final BigramTokenizer tokenizer = new BigramTokenizer();

    @Test
    void 中文按bigram切分() {
        assertEquals(List.of("内存", "存碎", "碎片"), tokenizer.tokens("内存碎片"));
    }

    @Test
    void 单个汉字原样保留() {
        assertEquals(List.of("堆"), tokenizer.tokens("堆"));
    }

    @Test
    void ASCII驼峰词整词保留并小写化() {
        assertEquals(List.of("concurrenthashmap"), tokenizer.tokens("ConcurrentHashMap"));
    }

    @Test
    void 中英混排与标点() {
        // 连续中文段滑窗切分："分别什么时候触发" → 分别/别什/什么/么时/时候/候触/触发
        List<String> tokens = tokenizer.tokens("Minor GC 和 Full GC 分别什么时候触发？");
        assertEquals(List.of("minor", "gc", "和", "full", "gc",
                "分别", "别什", "什么", "么时", "时候", "候触", "触发"), tokens);
    }

    @Test
    void tokenize输出空格分隔串供to_tsvector使用() {
        assertEquals("内存 存碎 碎片", tokenizer.tokenize("内存碎片"));
        assertEquals("", tokenizer.tokenize(null));
        assertEquals("", tokenizer.tokenize("，。！？"));
    }

    @Test
    void 数字与技术符号保留在词内() {
        assertEquals(List.of("g1", "jdk8"), tokenizer.tokens("G1 jdk8"));
    }
}
