package com.Myself.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagPipelineService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${rag.rank:false}")
    private boolean rerankEnabled;

    private static final double W_VEC = 0.6;
    private static final double W_BM25 = 0.4;
    private static final int CANDIDATE_NUM = 50;

    // 缓存：检索结果 60 秒
    private final Map<String, CacheEntry<List<ChunkResult>>> resultCache = new ConcurrentHashMap<>();
    private static final long RESULT_CACHE_TTL = 60 * 1000L;

    public RagPipelineService(JdbcTemplate jdbcTemplate,
                              EmbeddingService embeddingService,
                              LlmService llmService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.llmService = llmService;
    }

    /** 入口：返回增强检索的上下文文本 */
    public String buildContext(String userId, String query, int topK) {
        if(query==null || query.trim().isEmpty()|| isChitchat(query)){
            log.info("RAG跳过(闲聊):{}",query);
            return "";
        }

        double maxSim=0;
        try {
            List<Map<String, Object>> all = jdbcTemplate.queryForList(
                    "SELECT id, embedding FROM doc_embeddings");
            if (all.isEmpty()) return "";
            List<Float> qv = embeddingService.embed(query);
            maxSim = embeddingService.maxSimilarity(qv, all, objectMapper);
        }catch (Exception e) {
            log.warn("RAG遇见失败，继续走检索：{}",e.getMessage());
        }
        //档1：无关：完全跳过
            if (maxSim >0 && maxSim<0.50) {
                log.info("RAG跳过(无相关内容): query={}, maxSim={}", query, maxSim);
                return "";
            }
        log.info("RAG预检通过: query={}, maxSim={}", query, maxSim);
        List<ChunkResult> top = searchDirect(userId, query, topK);
        if (top.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【相关知识】\n");
        int totalChars=0;
        int maxChars=1500;
        for (ChunkResult r : top) {
            String piece=r.text.length()>500 ? r.text.substring(0,500):r.text;
            sb.append(piece).append("\n---\n");
            totalChars+=piece.length();
            if(totalChars>=maxChars) break;
        }
        log.info("RAG context注入, 长度={}(限制后)",sb.length());
        return sb.toString();
    }

    /** 弱相关档：单路检索（跳过改写），带独立缓存 */
    public List<ChunkResult> searchDirect(String userId, String query, int topK) {
        String cacheKey = "direct:" + query;
        CacheEntry<List<ChunkResult>> cached = resultCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.info("RAG结果缓存命中(direct): {}", query);
            return cached.value;
        }
        List<Float> qv = embeddingService.embed(query);
        List<ChunkResult> fastResults = hybridSearch(qv, query, topK);
        List<ChunkResult> top = rerankEnabled ? rerank(userId, query, fastResults, topK) : fastResults;
        List<ChunkResult> snapshot = List.copyOf(top);
        resultCache.put(cacheKey, new CacheEntry<>(snapshot, RESULT_CACHE_TTL));
        return snapshot;
    }
    /** 向量 + BM25 加权融合检索，BM25 在 SQL 层粗筛，省去全表计算 */
    private List<ChunkResult> hybridSearch(List<Float> queryVec, String keywords, int topK) {
        Map<Long, double[]> scores = new ConcurrentHashMap<>();  // id -> {cosine, bm25}
        Map<Long, String> texts = new LinkedHashMap<>();

        // BM25 粗筛：SQL 返回最相关的 50 条
        List<Map<String, Object>> ftRows;
        try {
            ftRows = jdbcTemplate.queryForList(
                    "SELECT id, chunk_text, embedding, MATCH(chunk_text) AGAINST(? IN NATURAL LANGUAGE MODE) AS score "
                            + "FROM doc_embeddings ORDER BY score DESC LIMIT " + CANDIDATE_NUM,
                    keywords);
        } catch (Exception e) {
            log.warn("BM25不可用，回退全表: {}", e.getMessage());
            ftRows = jdbcTemplate.queryForList(
                    "SELECT id, chunk_text, embedding FROM doc_embeddings LIMIT " + CANDIDATE_NUM);
        }

        for (Map<String, Object> row : ftRows) {
            long id = ((Number) row.get("id")).longValue();
            String text = (String) row.get("chunk_text");
            texts.put(id, text);
            double bm25 = row.containsKey("score") && row.get("score") != null
                    ? ((Number) row.get("score")).doubleValue() : 0.0;
            try {
                List<Float> docVec = objectMapper.readValue((String) row.get("embedding"),
                        new TypeReference<List<Float>>() {});
                double cosine = embeddingService.cosineSimilarity(queryVec, docVec);
                scores.put(id, new double[]{cosine, bm25});
            } catch (Exception e) {
                scores.put(id, new double[]{0.0, bm25});
            }
        }

        double maxVec = scores.values().stream().mapToDouble(v -> v[0]).max().orElse(0);
        double maxBm25 = scores.values().stream().mapToDouble(v -> v[1]).max().orElse(0);

        List<ChunkResult> results = new ArrayList<>();
        for (var entry : scores.entrySet()) {
            double vecNorm = maxVec > 0 ? entry.getValue()[0] / maxVec : 0;
            double bm25Norm = maxBm25 > 0 ? entry.getValue()[1] / maxBm25 : 0;
            double fused = W_VEC * vecNorm + W_BM25 * bm25Norm;
            if (fused > 0.1) {
                results.add(new ChunkResult(entry.getKey(), texts.getOrDefault(entry.getKey(), ""), fused));
            }
        }
        results.sort((a, b) -> Double.compare(b.fusedScore, a.fusedScore));
        return results.subList(0, Math.min(topK * 3, results.size()));
    }

    /** LLM 重排，候选不足时自动跳过 */
    private List<ChunkResult> rerank(String userId, String query, List<ChunkResult> candidates, int topN) {
        if (candidates.size() <= topN) return candidates;

        StringBuilder sb = new StringBuilder("问题：" + query + "\n\n候选段落：\n");
        for (int i = 0; i < candidates.size(); i++) {
            String snippet = candidates.get(i).text.length() > 200
                    ? candidates.get(i).text.substring(0, 200) : candidates.get(i).text;
            sb.append("[").append(i).append("] ").append(snippet).append("\n");
        }
        sb.append("\n按相关度从高到低选出最相关的").append(topN).append("条，只输出序号（逗号分隔）。");

        try {
            String result = llmService.chat(userId, List.of(Map.of("role", "user", "content", sb.toString())));
            List<Integer> picked = new ArrayList<>();
            for (String tok : result.split("[,，\\s]+")) {
                try {
                    int idx = Integer.parseInt(tok.trim());
                    if (idx >= 0 && idx < candidates.size() && !picked.contains(idx)) picked.add(idx);
                } catch (NumberFormatException ignored) { }
            }
            if (!picked.isEmpty()) {
                return picked.stream().map(candidates::get).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("RAG重排失败，使用原排序: {}", e.getMessage());
        }
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }

    public static class ChunkResult {
        public final long id;
        public final String text;
        public final double fusedScore;

        public ChunkResult(long id, String text, double fusedScore) {
            this.id = id;
            this.text = text;
            this.fusedScore = fusedScore;
        }
    }

    private static class CacheEntry<T> {
        final T value;
        final long expireAt;

        CacheEntry(T value, long ttl) {
            this.value = value;
            this.expireAt = System.currentTimeMillis() + ttl;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
    private boolean isChitchat(String query) {
        String q = query.trim();
        if (q.length() <= 4) return true;  // 太短不检索
        String[] keywords = {"你好", "您好", "hello", "hi", "在吗", "谢谢", "再见", "拜拜", "你是谁",
                "早上好", "晚上好", "下午好", "吃了吗", "干嘛呢", "哈哈", "嗯", "哦", "好的"};
        for (String k : keywords) {
            if (q.contains(k) && q.length() <= 10) return true;
        }
        return false;
    }
}