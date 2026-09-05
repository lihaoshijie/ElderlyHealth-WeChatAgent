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
public class NutritionTool {

    @Value("${tianApi.key}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public NutritionTool() {
        this.webClient = WebClient.builder()
                .baseUrl("https://apis.tianapi.com")
                .build();
    }

    @Tool(name = "food_nutrition", value = "查询食物营养成分")
    public String queryNutrition(
            @P("食物名称") String food) {

        if (food == null || food.trim().isEmpty()) {
            return "请告诉我你想查询什么食物的营养成分";
        }

        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/nutrient/index")
                            .queryParam("key", apiKey)
                            .queryParam("mode", 0)
                            .queryParam("word", food.trim())
                            .queryParam("num", 1)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("营养成分 API 响应: {}", response);
            JsonNode root = mapper.readTree(response);

            int code = root.path("code").asInt();
            if (code != 200) {
                String msg = root.path("msg").asText();
                return "查询失败：" + msg;
            }

            JsonNode r = root.path("result");
            return String.format(
                    "【%s】（%s）\n\n"
                    + "热量: %.1f 大卡\n"
                    + "蛋白质: %.1fg | 脂肪: %.1fg | 碳水: %.1fg\n"
                    + "膳食纤维: %.1fg | 胆固醇: %.1fmg\n\n"
                    + "维生素A: %.1fμg | 维生素C: %.1fmg | 维生素E: %.2fmg\n"
                    + "钙: %.1fmg | 铁: %.1fmg | 锌: %.2fmg\n"
                    + "钾: %.1fmg | 钠: %.1fmg | 磷: %.1fmg\n"
                    + "镁: %.1fmg | 硒: %.1fμg | 锰: %.2fmg | 铜: %.2fmg",
                    r.path("name").asText(),
                    r.path("type").asText(),
                    r.path("rl").asDouble(),
                    r.path("dbz").asDouble(),
                    r.path("zf").asDouble(),
                    r.path("shhf").asDouble(),
                    r.path("ssxw").asDouble(),
                    r.path("dgc").asDouble(),
                    r.path("wssa").asDouble(),
                    r.path("wsfc").asDouble(),
                    r.path("wsse").asDouble(),
                    r.path("gai").asDouble(),
                    r.path("tei").asDouble(),
                    r.path("xin").asDouble(),
                    r.path("jia").asDouble(),
                    r.path("la").asDouble(),
                    r.path("ling").asDouble(),
                    r.path("mei").asDouble(),
                    r.path("xi").asDouble(),
                    r.path("meng").asDouble(),
                    r.path("tong").asDouble());

        } catch (Exception e) {
            log.error("营养成分查询失败", e);
            return "营养成分查询失败，请稍后重试";
        }
    }
}
