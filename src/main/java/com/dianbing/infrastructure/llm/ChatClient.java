package com.dianbing.infrastructure.llm;

import java.util.List;
import java.util.Map;

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

    /**
     * 非流式补全（改造 v1 新增）：查询改写、LLM-as-judge 等短调用用，一次性返回完整文本。
     *
     * @param model 模型名，传 null 用默认 chat 模型（judge 必须传与生产不同的模型，避免自己评自己）
     * @param temperature 采样温度（改写要稳定：0.1）
     * @param topP        核采样（改写：0.3）
     * @param timeoutMs   请求级超时（改写超时即走规则兜底）
     */
    String complete(String model, String systemPrompt, String userPrompt,
                    double temperature, double topP, long timeoutMs) throws Exception;

    /** Non-streaming OpenAI-compatible tool call round used by the bounded campus Agent loop. */
    default ToolResponse completeWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           double temperature, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("This chat provider does not support tool calling");
    }

    record ToolCall(String id, String name, Map<String, Object> arguments) {}
    record ToolResponse(String content, List<ToolCall> toolCalls) {}

    interface Listener {
        void onDelta(String text);

        void onDone();
    }
}
