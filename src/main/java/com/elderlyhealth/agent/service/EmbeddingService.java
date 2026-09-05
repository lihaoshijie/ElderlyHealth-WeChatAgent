package com.elderlyhealth.agent.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class EmbeddingService {

    @Value("${llm.api-key}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<Float>> embedCache = new ConcurrentHashMap<>();

    public List<Float> embed(String text) {
        String key = text.length() > 100 ? text.substring(0, 100) : text;
        List<Float> cached = embedCache.get(key);
        if (cached != null) {
            log.info("Embedding 命中缓存: key={}", key);
            return cached;
        }
        try {
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model(TextEmbedding.Models.TEXT_EMBEDDING_V3)
                .apiKey(apiKey)
                .texts(List.of(text))
                .build();

            TextEmbedding embedding = new TextEmbedding();
            long t0 = System.currentTimeMillis();
            TextEmbeddingResult result = embedding.call(param);
            long t1 = System.currentTimeMillis();
            log.info("Embedding API 耗时: {}ms", t1 - t0);

            String embeddingStr = result.getOutput().getEmbeddings().get(0).getEmbedding().toString();

            List<Float> vector = new ArrayList<>();
            String clean = embeddingStr.replace("[", "").replace("]", "").trim();
            if (!clean.isEmpty()) {
                for (String s : clean.split(",")) {
                    vector.add(Float.parseFloat(s.trim()));
                }
            }
            embedCache.put(key, vector);
            log.info("Embedding 成功: 维度={}", vector.size());
            return vector;
        } catch (NoApiKeyException e) {
            log.error("Embedding API Key 未配置", e);
            throw new RuntimeException("Embedding 失败: API Key 未配置");
        } catch (Exception e) {
            log.error("Embedding 调用失败", e);
            throw new RuntimeException("Embedding 调用失败", e);
        }
    }

    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    public double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * 计算查询向量与知识库所有块的最高相似度（预检用）。
     * 低于阈值说明知识库没有相关内容，可跳过 RAG。
     */
    public double maxSimilarity(List<Float> queryVec, List<Map<String, Object>> rows, ObjectMapper mapper) {
        double max = 0;
        for (Map<String, Object> row : rows) {
            try {
                List<Float> docVec = mapper.readValue((String) row.get("embedding"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Float>>() {});
                double s = cosineSimilarity(queryVec, docVec);
                if (s > max) max = s;
            } catch (Exception ignored) { }
        }
        return max;
    }
}
