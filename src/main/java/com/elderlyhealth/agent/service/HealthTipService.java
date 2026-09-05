package com.elderlyhealth.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class HealthTipService {

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public HealthTipService(@Value("${tianApi.key}") String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl("https://apis.tianapi.com")
                .build();
        log.info("HealthTipService 初始化完成");
    }

    public String getTip(String word) {
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/healthskill/index")
                            .queryParam("key", apiKey)
                            .queryParam("word", word)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("健康小妙招 API 响应: {}", response);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("健康小妙招 API 调用失败", e);
            return null;
        }
    }

    private String parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.path("code").asInt() != 200) {
                return null;
            }
            JsonNode result = root.path("result");
            String title = result.path("title").asText();
            String content = result.path("content").asText();
            return "【健康小妙招】\n" + title + "\n" + content;
        } catch (Exception e) {
            log.error("解析健康小妙招响应失败", e);
            return null;
        }
    }
}
