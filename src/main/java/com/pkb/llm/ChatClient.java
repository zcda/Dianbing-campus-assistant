package com.pkb.llm;

/**
 * 对话模型客户端抽象。第一天就定义接口（借鉴 Provider 抽象），
 * 后续换厂商/SDK 只换实现，不动业务代码。
 */
public interface ChatClient {

    /**
     * 流式对话。回调在同一线程内同步执行；
     * 服务端异常或监听器抛出的异常直接向上传播。
     */
    void stream(String systemPrompt, String userPrompt, Listener listener) throws Exception;

    interface Listener {
        void onDelta(String text);

        void onDone();
    }
}
