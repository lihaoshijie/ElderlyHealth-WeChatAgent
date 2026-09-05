package com.Myself.demo.tool;

import com.Myself.demo.service.RagService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RagSearchTool {

    private final RagService ragService;

    public RagSearchTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool(name = "rag_search", value = {
        "搜索知识库文档，返回与关键词相关的知识片段。",
        "当用户询问专业知识、参考资料、规章制度、健康建议等内容时，",
        "优先调用本工具从知识库检索，再基于返回结果作答。"
    })
    public String ragSearch(
            @P("搜索关键词或问题") String keyword,
            @P("用户ID（自动传入）") String userId) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "请提供搜索关键词";
        }
        log.info("rag_search: userId={}, keyword={}", userId, keyword);
        List<Map<String, Object>> results = ragService.searchDocuments(keyword.trim());
        if (results.isEmpty()) {
            return "知识库中未找到与 \"" + keyword + "\" 相关的内容。";
        }
        return results.stream()
                .map(r -> String.format("[%s #%s] %s",
                        r.get("doc_name"),
                        r.get("chunk_index"),
                        r.get("chunk_text")))
                .collect(Collectors.joining("\n\n"));
    }
}
