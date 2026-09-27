package com.dianbing.qa.rewrite;

/**
 * 词表映射扩展点（FR-15，P2）：同义词/别名归一，纯规则零 token。
 * 例：jvm gc → JVM 垃圾回收。在改写之前执行，让"用户口语"与"笔记术语"对齐。
 */
public interface TermMapper {

    /** enabled=false 时原样返回 */
    String normalize(String text);
}
