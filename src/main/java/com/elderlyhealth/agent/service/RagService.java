package com.elderlyhealth.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RagService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagService(JdbcTemplate jdbcTemplate, EmbeddingService embeddingService, DocumentService documentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.documentService = documentService;
    }

    public void ingestDocument(byte[] bytes, String fileName) {
        ingestDocument(bytes, fileName, null);
    }

    public void ingestDocument(byte[] bytes, String fileName, String uploader) {
        log.info("===== RAG 入库开始: {}, uploader={} =====", fileName, uploader);
        try {
            String text = documentService.extractText(bytes, fileName);
            log.info("extractText 结果: text={}, length={}", text != null ? "非空" : "null", text != null ? text.length() : 0);
            if (text == null || text.isEmpty()) {
                log.warn("文档内容为空: {}", fileName);
                return;
            }
            jdbcTemplate.update("DELETE FROM doc_embeddings WHERE doc_name = ?", fileName);
            log.info("清除旧数据: {}", fileName);
            List<String> chunks = documentService.splitText(text, 1500, 150);
            log.info("文档 {} 切分为 {} 块", fileName, chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                log.info("开始嵌入第{}块, 文本前30字: {}", i, chunks.get(i).substring(0, Math.min(30, chunks.get(i).length())));
                try {
                    List<Float> vector = embeddingService.embed(chunks.get(i));
                    log.info("第{}块嵌入成功, 向量维度={}", i, vector.size());
                    String vectorJson = objectMapper.writeValueAsString(vector);
                    int rows = jdbcTemplate.update(
                        "INSERT INTO doc_embeddings (doc_name, chunk_index, chunk_text, embedding, user_id) VALUES (?, ?, ?, ?, ?)",
                        fileName, i, chunks.get(i), vectorJson, uploader
                    );
                    log.info("第{}块入库成功, rows={}", i, rows);
                } catch (Exception e) {
                    log.error("第{}块嵌入/入库失败: {}", i, e.getMessage(), e);
                }
            }
            log.info("文档 {} 已入库", fileName);
        } catch (Exception e) {
            log.error("入库整体失败: {}", fileName, e);
        }
        log.info("===== RAG 入库结束: {} =====", fileName);
    }

    public List<Map<String, Object>> listDocuments() {
        return jdbcTemplate.queryForList(
            "SELECT doc_name, COUNT(*) AS chunks, SUM(LENGTH(chunk_text)) AS chars "
            + "FROM doc_embeddings GROUP BY doc_name ORDER BY doc_name");
    }

    public List<Map<String, Object>> getDocumentChunks(String docName) {
        return jdbcTemplate.queryForList(
            "SELECT chunk_index, chunk_text FROM doc_embeddings WHERE doc_name = ? ORDER BY chunk_index", docName);
    }

    public List<Map<String, Object>> searchDocuments(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return List.of();
        try {

            List<Float> queryVec = embeddingService.embed(kw);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT doc_name, chunk_index, chunk_text, user_id, embedding FROM doc_embeddings");
            List<Map.Entry<Map<String, Object>, Double>> scored = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String embJson = (String) row.get("embedding");
                if (embJson == null || embJson.isEmpty()) continue;
                List<Float> docVec = objectMapper.readValue(embJson, new TypeReference<List<Float>>() {});
                double score = embeddingService.cosineSimilarity(queryVec, docVec);
                row.remove("embedding");
                scored.add(Map.entry(row, score));
            }
            scored.removeIf(e -> e.getValue() < 0.45);
            if (scored.isEmpty()) return List.of();

            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            double cut = scored.get(0).getValue() * 0.6;
            scored.removeIf(e -> e.getValue() < cut);

            int limit = Math.min(10, scored.size());
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < limit; i++) result.add(scored.get(i).getKey());
            return result;
        } catch (Exception e) {
            log.warn("语义检索失败, 回退 LIKE:{}:",e.getMessage());
            String like = "%" + kw + "%";
            return jdbcTemplate.queryForList(
                    "SELECT doc_name, chunk_index, chunk_text, uploader, uploaded_at FROM doc_embeddings "
                            + "WHERE doc_name LIKE ? OR chunk_text LIKE ? "
                            + "ORDER BY uploaded_at DESC LIMIT 100", like, like);
        }
    }
    public int deleteDocument(String docName) {
        return jdbcTemplate.update("DELETE FROM doc_embeddings WHERE doc_name = ?", docName);
    }

    public int countDocuments() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT doc_name) FROM doc_embeddings", Long.class);
        return c == null ? 0 : c.intValue();
    }
}
