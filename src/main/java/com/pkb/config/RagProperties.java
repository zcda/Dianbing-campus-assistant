package com.pkb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** OpenAI 兼容服务基地址，默认本地 Ollama */
    private String apiBaseUrl = "http://localhost:11434/v1";

    /** API Key：Ollama 无需鉴权，默认占位 "ollama"；云 API 通过环境变量 LLM_API_KEY 注入 */
    private String apiKey = "ollama";

    private String chatModel = "llama3.1:8b";

    /** 向量模型：bge-m3（1024 维、中英文友好）；生成模型（如 llama3.1）不能做向量化 */
    private String embeddingModel = "bge-m3";

    private int embeddingDimension = 1024;

    /** 向量检索返回的片段数 */
    private int topK = 5;

    /** 余弦相似度阈值：低于该值的片段视为不相关，直接回答"未找到"，不调用 LLM */
    private double minSimilarity = 0.30;

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = minSimilarity;
    }
}
