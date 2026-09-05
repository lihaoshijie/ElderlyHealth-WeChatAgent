package com.elderlyhealth.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CaipuService {

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public CaipuService(@Value("${tianApi.key}") String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl("https://apis.tianapi.com")
                .build();
        log.info("CaipuService 初始化完成");
    }

    public String search(String word) {
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/caipu/index")
                            .queryParam("key", apiKey)
                            .queryParam("word", word)
                            .queryParam("num", 5)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("菜谱 API 响应: {}", response);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("菜谱 API 调用失败", e);
            return null;
        }
    }

    private String parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.path("code").asInt() != 200) {
                return null;
            }
            JsonNode resultList = root.path("result").path("list");
            if (!resultList.isArray() || resultList.isEmpty()) {
                return "未找到相关菜谱";
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode item : resultList) {
                String name = item.path("cp_name").asText();
                String texing = item.path("texing").asText();
                String yuanliao = item.path("yuanliao").asText();
                String tiaoliao = item.path("tiaoliao").asText();
                String zuofa = item.path("zuofa").asText();
                String tishi = item.path("tishi").asText();

                sb.append("【").append(name).append("】\n");
                if (!texing.isEmpty()) sb.append("特点：").append(texing).append("\n");
                if (!yuanliao.isEmpty()) sb.append("原料：").append(yuanliao).append("\n");
                if (!tiaoliao.isEmpty()) sb.append("调料：").append(tiaoliao).append("\n");
                if (!zuofa.isEmpty()) sb.append("做法：\n").append(zuofa).append("\n");
                if (!tishi.isEmpty()) sb.append("提示：").append(tishi).append("\n");
                sb.append("---\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("解析菜谱响应失败", e);
            return null;
        }
    }
}
