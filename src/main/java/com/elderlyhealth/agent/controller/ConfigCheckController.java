package com.elderlyhealth.agent.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ConfigCheckController {

    @Value("${llm.api-key:}")
    private String llmKey;

    @Value("${amap.key:}")
    private String amapKey;

    @Value("${seniverse.api.key:}")
    private String seniverseKey;

    @Value("${tianapi.api.key:}")
    private String tianapiKey;

    @Value("${flyai.api-key:}")
    private String flyaiKey;

    @Value("${juhe.api.key:}")
    private String juheKey;

    @Value("${serpapi.key:}")
    private String serpapiKey;

    @Value("${qianfan.api.key:}")
    private String qianfanKey;

    @Value("${delivery.app-id:}")
    private String deliveryAppId;

    @Value("${delivery.api-key:}")
    private String deliveryApiKey;

    @GetMapping("/config/check")
    public Map<String, Object> check() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llm.api-key (A_BAILIAN_API_KEY)", mask(llmKey));
        result.put("amap.key (GAODE_API_KEY)", mask(amapKey));
        result.put("seniverse.api.key (SENIVERSE_API_KEY)", mask(seniverseKey));
        result.put("tianapi.api.key (TIAN_API_KEY)", mask(tianapiKey));
        result.put("flyai.api-key (FLYAI_API_KEY)", mask(flyaiKey));
        result.put("juhe.api.key (JUHE_API_KEY)", mask(juheKey));
        result.put("serpapi.key (SERPAPI_KEY)", mask(serpapiKey));
        result.put("qianfan.api.key (QIANFAN_API_KEY)", mask(qianfanKey));
        result.put("delivery.app-id (DELIVERY_APP_ID)", mask(deliveryAppId));
        result.put("delivery.api-key (DELIVERY_API_KEY)", mask(deliveryApiKey));
        return result;
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "【未配置】";
        }
        if (value.length() <= 8) {
            return value.substring(0, 2) + "***";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
