package com.pkb.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pkb.config.RagProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议实现（默认对接阿里云百炼 DashScope 兼容模式）。
 * Embedding 走 RestClient（同步批量）；Chat 流式走 JDK HttpClient 解析 SSE。
 */
@Component
public class OpenAiCompatClient implements ChatClient, EmbeddingClient {

    /** DashScope text-embedding-v3 单批最多 10 条 */
    private static final int EMBED_BATCH_SIZE = 10;

    private final RagProperties props;
    private final RestClient rest;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OpenAiCompatClient(RagProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.rest = builder.requestFactory(factory).build();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = mapper;
    }

    private void requireApiKey() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("未配置 LLM API Key（请设置环境变量 LLM_API_KEY 后重启）");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<float[]> embedBatch(List<String> texts) {
        requireApiKey();
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += EMBED_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + EMBED_BATCH_SIZE, texts.size()));
            Map<String, Object> body = Map.of("model", props.getEmbeddingModel(), "input", batch);
            Map<String, Object> resp = rest.post()
                    .uri(props.getApiBaseUrl() + "/embeddings")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            all.addAll(parseEmbeddings(resp, batch.size()));
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private List<float[]> parseEmbeddings(Map<String, Object> resp, int expected) {
        List<Map<String, Object>> data = resp == null ? null : (List<Map<String, Object>>) resp.get("data");
        if (data == null || data.size() != expected) {
            throw new IllegalStateException("Embedding 返回数量不符: 期望 " + expected + " 条, 实际 "
                    + (data == null ? 0 : data.size()) + " 条");
        }
        float[][] ordered = new float[expected][];
        for (Map<String, Object> item : data) {
            int index = ((Number) item.get("index")).intValue();
            List<Number> vector = (List<Number>) item.get("embedding");
            if (vector.size() != props.getEmbeddingDimension()) {
                throw new IllegalStateException("Embedding 维度不符: 配置 " + props.getEmbeddingDimension()
                        + ", 实际 " + vector.size() + "（请检查 rag.embedding-dimension）");
            }
            float[] values = new float[vector.size()];
            for (int j = 0; j < values.length; j++) {
                values[j] = vector.get(j).floatValue();
            }
            ordered[index] = values;
        }
        return Arrays.asList(ordered);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void stream(String systemPrompt, String userPrompt, Listener listener) throws Exception {
        requireApiKey();
        Map<String, Object> body = Map.of(
                "model", props.getChatModel(),
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.getApiBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(300))
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("LLM 调用失败 HTTP " + response.statusCode() + ": "
                    + truncate(error, 300));
        }
        boolean doneNotified = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if (payload.equals("[DONE]")) {
                    listener.onDone();
                    doneNotified = true;
                    return;
                }
                Map<String, Object> event = mapper.readValue(payload, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) event.get("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                if (delta == null) {
                    continue;
                }
                Object content = delta.get("content");
                if (content instanceof String text && !text.isEmpty()) {
                    listener.onDelta(text);
                }
            }
        }
        if (!doneNotified) {
            // 流结束但未收到 [DONE]，仍视为正常完成
            listener.onDone();
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
