package com.Myself.demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class BaiduSearchTool {

    @Value("${qianfan.api.key}")
    private String apiKey;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public BaiduSearchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Tool(name = "baidu_search", value = "搜索国内信息/新闻/官网")
    public String search(
            @P("搜索关键词") String keyword) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return "请告诉我你想搜索什么";
        }

        try {
            var body = mapper.createObjectNode();
            body.put("search_source", "baidu_search_v2");

            var messages = mapper.createArrayNode();
            var msg = mapper.createObjectNode();
            msg.put("role", "user");
            msg.put("content", keyword.trim());
            messages.add(msg);
            body.set("messages", messages);

            var filter = mapper.createArrayNode();
            var web = mapper.createObjectNode();
            web.put("type", "web");
            web.put("top_k", 10);
            filter.add(web);
            body.set("resource_type_filter", filter);

            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://qianfan.baidubce.com/v2/ai_search/web_search"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("百度搜索API错误: status={}, body={}", response.statusCode(), response.body());
                return "搜索失败，请稍后重试";
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode refs = root.path("references");

            if (!refs.isArray() || refs.isEmpty()) {
                return "未找到相关搜索结果";
            }

            StringBuilder sb = new StringBuilder("搜索结果：\n");
            for (int i = 0; i < refs.size(); i++) {
                JsonNode r = refs.get(i);
                sb.append(i + 1).append(". ").append(r.path("title").asText()).append("\n");
                String url = r.path("url").asText();
                if (!url.isEmpty()) sb.append("   ").append(url).append("\n");
                String content = r.path("content").asText();
                if (!content.isEmpty()) {
                    sb.append("   ").append(content.length() > 120
                            ? content.substring(0, 120) + "..."
                            : content).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("百度搜索失败", e);
            return "搜索失败，请稍后重试";
        }
    }
}
