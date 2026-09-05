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
public class NewsTool {

    @Value("${juhe.api.key}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public NewsTool() {
        this.webClient = WebClient.builder()
                .baseUrl("https://apis.juhe.cn")
                .build();
    }

    @Tool(name = "ai_news", value = "查询AI新闻简报")
    public String queryNews(
            @P("新闻分类") String type,
            @P("页码，默认1") int page,
            @P("每页条数，默认5，最大20") int pageSize) {

        if (page <= 0) page = 1;
        if (pageSize <= 0) pageSize = 5;
        final int p = page;
        final int ps = pageSize;
        final String t = (type != null && !type.trim().isEmpty()) ? type.trim() : null;

        try {
            String response;
            if (t != null) {
                response = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/fapigw/aibrief/list")
                                .queryParam("key", apiKey)
                                .queryParam("type", t)
                                .queryParam("page", p)
                                .queryParam("page_size", ps)
                                .build())
                        .retrieve().bodyToMono(String.class).block();
            } else {
                response = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/fapigw/aibrief/list")
                                .queryParam("key", apiKey)
                                .queryParam("page", p)
                                .queryParam("page_size", ps)
                                .build())
                        .retrieve().bodyToMono(String.class).block();
            }
            log.debug("新闻 API 响应长度: {}", response != null ? response.length() : 0);
            JsonNode root = mapper.readTree(response);

            int code = root.path("error_code").asInt(-1);
            if (code != 0) {
                String reason = root.path("reason").asText();
                return "查询失败：" + reason;
            }

            JsonNode list = root.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) {
                return "暂无新闻";
            }

            StringBuilder sb = new StringBuilder("【AI新闻简报】\n\n");
            for (int i = 0; i < list.size(); i++) {
                JsonNode n = list.get(i);
                sb.append(i + 1).append(". ").append(n.path("title").asText()).append("\n");
                String summary = n.path("summary").asText();
                if (!summary.isEmpty()) {
                    sb.append("   ").append(summary.length() > 120
                            ? summary.substring(0, 120) + "..."
                            : summary).append("\n");
                }
                sb.append("   来源：").append(n.path("author_name").asText());
                sb.append(" | ").append(n.path("publish_date").asText()).append("\n\n");
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("新闻查询失败", e);
            return "新闻查询失败，请稍后重试";
        }
    }
}
