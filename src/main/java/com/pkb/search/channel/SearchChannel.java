package com.pkb.search.channel;

/**
 * 检索通道扩展点（设计文档 D2）：一通路的召回，返回原始有序列表 + 分数。
 * 实现方各自从 RagProperties.channels.* 读取开关。
 */
public interface SearchChannel {

    SearchChannelType type();

    /** 通道总开关（来自配置）；关闭后该通道完全不参与召回，链路仍然正确（fail-open） */
    boolean isEnabled();

    /** 执行一次召回，返回按名次有序的原始结果 */
    SearchChannelResult search(SearchContext ctx);
}
