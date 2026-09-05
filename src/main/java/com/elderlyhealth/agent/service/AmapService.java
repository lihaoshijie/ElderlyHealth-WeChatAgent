package com.elderlyhealth.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

@Slf4j
@Service
public class AmapService {

    private final WebClient client = WebClient.create("https://restapi.amap.com");
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${amap.key:}")
    private String apiKey;

    public String getCoord(String city, String address) {
        try {
            String json = client.get()
                    .uri(uri -> uri.path("/v3/geocode/geo")
                            .queryParam("key", apiKey)
                            .queryParam("address", address)
                            .queryParam("city", city)
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = mapper.readTree(json);
            if (!"1".equals(root.get("status").asText())) return null;
            JsonNode geos = root.get("geocodes");
            if (geos == null || !geos.isArray() || geos.isEmpty()) return null;
            return geos.get(0).get("location").asText();
        } catch (Exception e) {
            log.warn("地理编码失败: city={}, addr={}", city, address, e);
            return null;
        }
    }

    public int transitDistance(String origin, String destination, String city) {
        try {
            String json = client.get()
                    .uri(uri -> uri.path("/v3/direction/transit/integrated")
                            .queryParam("key", apiKey)
                            .queryParam("origin", origin)
                            .queryParam("destination", destination)
                            .queryParam("city", city)
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = mapper.readTree(json);
            if (!"1".equals(root.get("status").asText())) return 9999;
            return root.get("route").get("transits").get(0).get("distance").asInt();
        } catch (Exception e) {
            log.warn("公交距离计算失败", e);
            return 9999;
        }
    }

    /**
     * 查询两个景点（坐标，格式"经度,纬度"）之间的通行方案文本。
     * 返回如 "公交约40分钟 / 驾车约30分钟"，对应接口失败或缺数据时省略该部分。
     */
    public String transitPlan(String origin, String destination, String city) {
        List<String> parts = new ArrayList<>();

        try {
            String json = client.get()
                    .uri(uri -> uri.path("/v3/direction/transit/integrated")
                            .queryParam("key", apiKey)
                            .queryParam("origin", origin)
                            .queryParam("destination", destination)
                            .queryParam("city", city)
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = mapper.readTree(json);
            if ("1".equals(root.path("status").asText())) {
                JsonNode transits = root.path("route").path("transits");
                if (transits.isArray() && !transits.isEmpty()) {
                    int seconds = transits.get(0).path("duration").asInt();
                    if (seconds > 0) {
                        parts.add("公交约" + Math.round(seconds / 60.0) + "分钟");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("公交方案查询失败: {} -> {}", origin, destination, e);
        }

        try {
            String json = client.get()
                    .uri(uri -> uri.path("/v3/direction/driving")
                            .queryParam("key", apiKey)
                            .queryParam("origin", origin)
                            .queryParam("destination", destination)
                            .queryParam("strategy", 0)
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = mapper.readTree(json);
            if ("1".equals(root.path("status").asText())) {
                JsonNode paths = root.path("route").path("paths");
                if (paths.isArray() && !paths.isEmpty()) {
                    int seconds = paths.get(0).path("duration").asInt();
                    if (seconds > 0) {
                        parts.add("驾车约" + Math.round(seconds / 60.0) + "分钟");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("驾车方案查询失败: {} -> {}", origin, destination, e);
        }

        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    public List<Map<String, Object>> searchPoi(String city, String keywords) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String json = client.get()
                    .uri(uri -> uri.path("/v3/place/text")
                            .queryParam("key", apiKey)
                            .queryParam("keywords", keywords)
                            .queryParam("city", city)
                            .queryParam("offset", 20)
                            .queryParam("page", 1)
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = mapper.readTree(json);
            if (!"1".equals(root.get("status").asText())) return result;
            for (JsonNode p : root.get("pois")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.get("name").asText());
                m.put("location", p.get("location").asText());
                m.put("address", p.has("address") ? p.get("address").asText() : "");
                m.put("type", p.has("type") ? p.get("type").asText() : "");
                result.add(m);
            }
        } catch (Exception e) {
            log.warn("POI搜索失败", e);
        }
        return result;
    }
}
