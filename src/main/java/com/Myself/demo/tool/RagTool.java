package com.Myself.demo.tool;

import com.Myself.demo.service.RagService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RagTool {

    private final RagService ragService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    public RagTool(RagService ragService) { this.ragService = ragService; }

    @Tool(name = "rag_search", value = "知识库检索，可查文档内容或列出文件")
    public String search(@P("用户问题") String query, @P("返回块数") int topK,
                         @P("用户ID,系统自动填充") String userId) {
        log.info("RAG检索: {} (user={})", query, userId);
        if (topK <= 0) topK = 3;

        if (isListQuery(query) && jdbcTemplate != null) {
            try {
                var docs = userId != null ? jdbcTemplate.queryForList(
                    "SELECT doc_name, COUNT(*) AS chunks FROM doc_embeddings WHERE user_id=? OR user_id IS NULL GROUP BY doc_name", userId)
                    : jdbcTemplate.queryForList("SELECT doc_name, COUNT(*) AS chunks FROM doc_embeddings GROUP BY doc_name");
                if (docs.isEmpty()) return "知识库暂无文件";
                var sb = new StringBuilder("知识库文件：\n");
                docs.forEach(d -> sb.append("- ").append(d.get("doc_name")).append("(").append(d.get("chunks")).append("块)\n"));
                return sb.toString();
            } catch (Exception e) { log.warn("查询KB列表失败", e); }
        }

        var docs = ragService.searchDocuments(query);
        if (docs.isEmpty()) return "知识库未找到相关信息";
        var sb = new StringBuilder("【知识库】\n");
        docs.forEach(d -> sb.append(d.get("chunk_text")).append("\n---\n"));
        return sb.toString();
    }

    @Tool(name = "kb_delete_file", value = "删除知识库文件")
    public String deleteFile(@P("文件名") String fileName, @P("用户ID,系统自动填充") String userId) {
        if (jdbcTemplate == null) return "服务不可用";
        try {
            int n = userId != null ? jdbcTemplate.update("DELETE FROM doc_embeddings WHERE doc_name=? AND (user_id=? OR user_id IS NULL)", fileName, userId)
                    : jdbcTemplate.update("DELETE FROM doc_embeddings WHERE doc_name=?", fileName);
            return n > 0 ? "已删除" + fileName : "未找到该文件";
        } catch (Exception e) { return "删除失败"; }
    }

    private boolean isListQuery(String q) {
        if (q == null) return false;
        return q.contains("有哪些") || q.contains("什么文件") || q.contains("文件列表")
            || q.contains("看看知识库") || q.contains("知识库有什么") || q.contains("列出");
    }
}
