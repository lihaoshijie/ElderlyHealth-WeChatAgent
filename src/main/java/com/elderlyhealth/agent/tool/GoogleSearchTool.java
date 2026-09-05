package com.elderlyhealth.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class GoogleSearchTool {

    @Value("${serpapi.key}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleSearchTool() {
        this.webClient = WebClient.builder()
                .baseUrl("https://serpapi.com")
                .build();
    }

    @Tool(name = "google_search", value = "搜索海外国际信息")
    public String search(
            @P("搜索关键词") String keyword,
            @P("每页结果数，默认5，上限10") int num,
            @P("结果起始位置，0=第一页 10=第二页") int start) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return "请告诉我你想搜索什么";
        }
        if (num <= 0) num = 5;
        if (start < 0) start = 0;
        final int n = num;
        final int s = start;
        final String k = keyword.trim();

        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("engine", "google")
                            .queryParam("api_key", apiKey)
                            .queryParam("q", k)
                            .queryParam("num", n)
                            .queryParam("start", s)
                            .queryParam("gl", "cn")
                            .queryParam("hl", "zh-cn")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = mapper.readTree(response);

            StringBuilder sb = new StringBuilder();

            // 知识面板（官网地址优先）
            JsonNode kg = root.path("knowledge_graph");
            if (!kg.isMissingNode() && kg.has("website")) {
                sb.append("官方网址: ").append(kg.path("website").asText()).append("\n\n");
            }

            // 自然搜索结果
            JsonNode organics = root.path("organic_results");
            if (organics.isArray()) {
                sb.append("搜索结果：\n");
                for (int i = 0; i < organics.size(); i++) {
                    JsonNode r = organics.get(i);
                    sb.append(i + 1).append(". ").append(r.path("title").asText()).append("\n");
                    sb.append("   ").append(r.path("link").asText()).append("\n");
                    String snippet = r.path("snippet").asText();
                    if (!snippet.isEmpty()) {
                        sb.append("   ").append(snippet.length() > 120
                                ? snippet.substring(0, 120) + "..."
                                : snippet).append("\n");
                    }
                    sb.append("\n");
                }
            }

            if (sb.isEmpty()) {
                return "未找到相关搜索结果";
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("Google搜索失败", e);
            return "搜索失败，请稍后重试";
        }
    }
}
