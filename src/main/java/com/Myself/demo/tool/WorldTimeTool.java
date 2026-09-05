package com.Myself.demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class WorldTimeTool {

    @Value("${tianApi.key}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorldTimeTool() {
        this.webClient = WebClient.builder()
                .baseUrl("https://apis.tianapi.com")
                .build();
    }

    @Tool(name = "world_time", value = "查询全球城市当前时间")
    public String queryWorldTime(
            @P("城市名称") String city) {

        if (city == null || city.trim().isEmpty()) {
            return "请告诉我你想查询哪个城市的时间";
        }

        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/worldtime/index")
                            .queryParam("key", apiKey)
                            .queryParam("city", city.trim())
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("世界时间 API 响应: {}", response);
            JsonNode root = mapper.readTree(response);

            int code = root.path("code").asInt();
            if (code != 200) {
                String msg = root.path("msg").asText();
                return "查询失败：" + msg;
            }

            JsonNode r = root.path("result");
            return String.format(
                    "【%s / %s】\n\n时间: %s %s\n时区: %s\n日期: %s月 星期%s\n国家: %s\n电话区号: +%s",
                    r.path("city").asText(),
                    r.path("encity").asText(),
                    r.path("strtime").asText(),
                    r.path("noon").asText(),
                    r.path("timeZone").asText(),
                    r.path("nowmonth").asText(),
                    r.path("week").asText(),
                    r.path("country").asText(),
                    r.path("countrycode").asText());

        } catch (Exception e) {
            log.error("世界时间查询失败", e);
            return "时间查询失败，请稍后重试";
        }
    }

    @Tool(name = "local_time", value = "获取当前系统时间")
    public String localTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss EEEE");
        return "北京时间：" + now.format(fmt);
    }
}
