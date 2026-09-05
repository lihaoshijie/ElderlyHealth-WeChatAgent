package com.elderlyhealth.agent.service;

import com.elderlyhealth.agent.service.LlmService.LlmResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TravelPlanService {

    private static final int MAX_FIXES = 3;
    private static final int MAX_PER_DAY = 2;

    private final LlmService llmService;
    private final AmapService amapService;
    private final Map<String, String> transitCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public TravelPlanService(LlmService llmService, AmapService amapService) {
        this.llmService = llmService;
        this.amapService = amapService;
    }

    public List<Map<String, Object>> searchAttractions(String city, String category) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String flyaiPath = System.getenv("APPDATA") + "\\npm\\flyai.cmd";
            List<String> cmd = new ArrayList<>(List.of(flyaiPath, "search-poi", "--city-name", city));
            if (category != null && !category.isEmpty()) {
                cmd.add("--category");
                cmd.add(category);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line);
            process.waitFor();

            String json = output.toString();
            int braceIndex = json.indexOf("{");
            if (braceIndex < 0) return result;
            if (braceIndex > 0) json = json.substring(braceIndex);

            JsonNode root = mapper.readTree(json);
            if (root.get("status").asInt() != 0) return result;

            JsonNode items = root.get("data").get("itemList");
            if (items == null || !items.isArray()) return result;

            for (JsonNode item : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", item.get("name").asText());
                JsonNode ticketInfo = item.get("ticketInfo");
                String price = ticketInfo != null && ticketInfo.has("price")
                        ? ticketInfo.get("price").asText() : "";
                m.put("price", price);
                m.put("free", "FREE".equals(item.path("freePoiStatus").asText()));
                m.put("category", item.hasNonNull("category") ? item.get("category").asText() : "");
                m.put("level", item.hasNonNull("poiLevel") ? item.get("poiLevel").asText() : "");
                m.put("lat", item.hasNonNull("latitude") ? item.get("latitude").asText() : "");
                m.put("lng", item.hasNonNull("longitude") ? item.get("longitude").asText() : "");
                String desc = item.hasNonNull("description") ? item.get("description").asText() : "";
                m.put("description", desc.length() > 200 ? desc.substring(0, 200) + "..." : desc);
                result.add(m);
            }
        } catch (Exception e) {
            log.error("搜索景点失败", e);
        }
        return result;
    }

    public String enrichAttractionsWithCoords(String city, List<Map<String, Object>> attractions) {
        StringBuilder sb = new StringBuilder();
        sb.append("在「").append(city).append("」找到以下景点：\n\n");
        int idx = 0;
        for (Map<String, Object> a : attractions) {
            idx++;
            sb.append(idx).append(". ").append(a.get("name"))
                    .append("  ").append(priceLabel(a)).append("\n");
            String desc = (String) a.get("description");
            if (desc != null && !desc.isEmpty()) {
                sb.append("   ").append(desc).append("\n");
            }
        }
        return sb.toString();
    }

    public String generatePlans(String city, int days, String interests, int plan,
                                List<Map<String, Object>> attractions) {
        if (attractions.isEmpty()) return "未找到景点数据，无法生成行程。";

        Map<String, Double> prices = estimatePrices(city, attractions);
        log.info("旅游 Loop 启动: 城市={}, 天数={}, 偏好={}", city, days, interests);

        List<Map<String, Object>> classic = classicPool(attractions);
        List<Map<String, Object>> niche = nichePool(attractions, classic);
        List<Map<String, Object>> pref = interestPool(attractions, interests);
        boolean hasPref = interests != null && !interests.isBlank() && hasMappedCategory(interests);
        List<String> names = List.of("经典必玩", hasPref ? "偏好主题" : "深度小众");
        List<List<Map<String, Object>>> pools = List.of(classic, hasPref ? pref : niche);

        StringBuilder sb = new StringBuilder();
        sb.append("🏖 ").append(city).append(days).append("日游");
        if (interests != null && !interests.isBlank()) sb.append(" · 偏好:").append(interests);
        sb.append("\n");

        for (int i = 0; i < pools.size(); i++) {
            if (plan > 0 && plan != i + 1) continue;
            List<List<Map<String, Object>>> daysPlan = runPlanLoop(pools.get(i), days, prices, attractions);
            sb.append("\n【方案").append(cnNum(i + 1)).append("：").append(names.get(i)).append("】\n");
            double totalTicket = 0;
            for (int d = 0; d < daysPlan.size(); d++) {
                List<Map<String, Object>> day = daysPlan.get(d);
                sb.append("第").append(d + 1).append("天：");
                double dayCost = 0;
                for (int j = 0; j < day.size(); j++) {
                    Map<String, Object> poi = day.get(j);
                    double price = attractionPrice(poi, prices);
                    dayCost += price;
                    totalTicket += price;
                    if (j > 0) {
                        sb.append(" → ").append(poi.get("name"));
                        String travel = transitBetween(city, day.get(j - 1), poi);
                        if (travel != null) sb.append("（").append(travel).append("）");
                    } else {
                        sb.append(poi.get("name"));
                    }
                    sb.append(price > 0 ? "(约¥" + (long) price + ")" : "(免费)");
                }
                sb.append("\n");
                if (dayCost > 0) {
                    sb.append("   门票约¥").append((long) dayCost).append("/人\n");
                }
            }
            sb.append("门票合计约¥").append((long) totalTicket).append("/人\n");
        }
        sb.append("\n---\n回复「方案一/二/三」选择，或告诉我怎么调整。");
        return sb.toString();
    }

    /**
     * 生成全面整体行程安排：出发（去程交通+首日安排）、当地逐日安排、返程（返程交通）。
     * transport 传 "火车"/"飞机"，缺省时 LLM 一般根据用户选定传递。
     */
    public String generateFullItinerary(String city, int days, String fromCity, String interests,
                                        int plan, String transport, List<Map<String, Object>> attractions) {
        if (attractions.isEmpty()) return "未找到景点数据，无法生成行程。";

        Map<String, Double> prices = estimatePrices(city, attractions);
        List<Map<String, Object>> classic = classicPool(attractions);
        List<Map<String, Object>> niche = nichePool(attractions, classic);
        List<Map<String, Object>> pref = interestPool(attractions, interests);
        boolean hasPref = interests != null && !interests.isBlank() && hasMappedCategory(interests);

        List<Map<String, Object>> pool;
        String planName;
        if (plan == 2) {
            pool = hasPref ? pref : niche;
            planName = hasPref ? "偏好主题" : "深度小众";
        } else {
            pool = classic;
            planName = "经典必玩";
        }
        List<List<Map<String, Object>>> daysPlan = runPlanLoop(pool, days, prices, attractions);
        boolean byFlight = transport != null && transport.contains("飞");
        String from = fromCity == null || fromCity.isBlank() ? "出发城市" : fromCity;

        StringBuilder sb = new StringBuilder();
        sb.append("🧳 ").append(city).append(days).append("日游 · 整体行程安排（方案").append(cnNum(plan)).append("：").append(planName).append("）\n\n");

        sb.append("【出发】\n");
        sb.append("🚄/✈️ 去程：").append(from).append(" → ").append(city);
        if (byFlight) {
            sb.append("（机票）\n建议提前到达机场，具体航班以订票链接实时查询为准。");
        } else {
            sb.append("（火车）\n建议选择上午发车的高铁/动车，中午前后抵达。");
        }
        sb.append("\n");

        double totalTicket = 0;
        for (int d = 0; d < daysPlan.size(); d++) {
            List<Map<String, Object>> day = daysPlan.get(d);
            sb.append("\n第").append(d + 1).append("天：");
            if (d == 0) sb.append("（抵达+首日游览）");
            if (d == daysPlan.size() - 1) sb.append("（最后一天，安排返程）");
            sb.append("\n");
            double dayCost = 0;
            for (int j = 0; j < day.size(); j++) {
                Map<String, Object> poi = day.get(j);
                double price = attractionPrice(poi, prices);
                dayCost += price;
                totalTicket += price;
                if (j > 0) {
                    sb.append(" → ").append(poi.get("name"));
                    String travel = transitBetween(city, day.get(j - 1), poi);
                    if (travel != null) sb.append("（").append(travel).append("）");
                } else {
                    sb.append("   ").append(poi.get("name"));
                }
                sb.append(price > 0 ? "(约¥" + (long) price + ")" : "(免费)");
            }
            sb.append("\n");
            if (dayCost > 0) sb.append("   当日门票约¥").append((long) dayCost).append("/人\n");
        }

        sb.append("\n【返程】\n");
        sb.append("🚄/✈️ 返程：").append(city).append(" → ").append(from);
        if (byFlight) {
            sb.append("（机票）\n建议安排在最后一天下午，预留充足时间前往机场。");
        } else {
            sb.append("（火车）\n建议安排在最后一天傍晚，可玩满当天行程再出发。");
        }
        sb.append("\n\n💰 门票合计约¥").append((long) totalTicket).append("/人（往返交通另计，见订票链接）\n");
        sb.append("\n---\n如需调整方案、交通方式或日期，直接告诉我。");
        return sb.toString();
    }

    List<List<Map<String, Object>>> runPlanLoop(List<Map<String, Object>> pool, int days,
                                                Map<String, Double> prices, List<Map<String, Object>> fullPool) {
        List<Map<String, Object>> current = new ArrayList<>(pool);
        for (int round = 1; round <= MAX_FIXES; round++) {
            List<List<Map<String, Object>>> daysPlan = schedule(current, days);
            List<String> issues = verify(daysPlan, days);
            if (issues.isEmpty()) {
                log.info("方案校验通过, 第{}轮收敛", round);
                return daysPlan;
            }
            log.info("方案校验未通过(第{}轮), 调整: {}", round, issues);
            current = applyFixes(current, issues, fullPool);
        }
        return schedule(current, days);
    }

    private List<List<Map<String, Object>>> schedule(List<Map<String, Object>> pois, int days) {
        List<List<Map<String, Object>>> result = new ArrayList<>();
        if (pois.isEmpty() || days <= 0) return result;
        List<Map<String, Object>> remaining = new ArrayList<>(pois);
        for (int d = 0; d < days && !remaining.isEmpty(); d++) {
            List<Map<String, Object>> day = new ArrayList<>();
            for (int i = 0; i < MAX_PER_DAY && !remaining.isEmpty(); i++) {
                day.add(remaining.remove(0));
            }
            result.add(day);
        }
        return result;
    }

    private List<String> verify(List<List<Map<String, Object>>> daysPlan, int days) {
        List<String> issues = new ArrayList<>();
        if (daysPlan.size() < days) {
            issues.add("景点不足" + days + "天行程");
        }
        return issues;
    }

    private List<Map<String, Object>> applyFixes(List<Map<String, Object>> pool, List<String> issues,
                                                 List<Map<String, Object>> fullPool) {
        List<Map<String, Object>> newPool = new ArrayList<>(pool);
        if (issues.stream().anyMatch(s -> s.contains("景点不足"))) {
            for (Map<String, Object> cand : fullPool) {
                if (isFree(cand) && !newPool.contains(cand)) newPool.add(cand);
            }
        }
        return newPool;
    }

    private List<Map<String, Object>> classicPool(List<Map<String, Object>> attractions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String lv : List.of("5", "4")) {
            for (Map<String, Object> a : attractions) {
                if (lv.equals(a.get("level")) && !result.contains(a)) result.add(a);
            }
        }
        for (Map<String, Object> a : attractions) {
            if (!result.contains(a)) result.add(a);
        }
        return result.size() > 6 ? new ArrayList<>(result.subList(0, 6)) : result;
    }

    private List<Map<String, Object>> nichePool(List<Map<String, Object>> attractions, List<Map<String, Object>> classic) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> a : attractions) {
            if (!classic.contains(a)) result.add(a);
        }
        return result;
    }

    private List<Map<String, Object>> interestPool(List<Map<String, Object>> attractions, String interests) {
        String s = interests == null ? "" : interests;
        List<String> cats = new ArrayList<>();
        boolean byDesc = false;
        if (containsAny(s, "自然", "山水", "风光", "风景", "户外", "森林")) {
            cats.addAll(List.of("山湖田园", "公园乐园"));
            byDesc = true;
        } else if (containsAny(s, "历史", "古迹", "人文", "文化", "陵", "寺")) {
            cats.addAll(List.of("历史古迹", "人文古迹", "纪念馆"));
        } else if (containsAny(s, "博物馆", "科技", "展")) {
            cats.add("博物馆");
        } else if (containsAny(s, "亲子", "动物", "小孩")) {
            cats.add("动物园");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> a : attractions) {
            String cat = (String) a.get("category");
            if (cats.contains(cat)) {
                result.add(a);
            } else if (byDesc && descMatches(a, s)) {
                result.add(a);
            }
        }
        if (result.isEmpty()) return new ArrayList<>(attractions);
        return result;
    }

    private boolean descMatches(Map<String, Object> a, String s) {
        String desc = (String) a.get("description");
        if (desc == null) return false;
        if (containsAny(s, "自然", "山水", "风光", "风景", "户外", "森林")) {
            return desc.contains("山") || desc.contains("湖") || desc.contains("林") || desc.contains("园")
                    || desc.contains("峰") || desc.contains("景");
        }
        return false;
    }

    private boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private Map<String, Double> estimatePrices(String city, List<Map<String, Object>> attractions) {
        Map<String, Double> prices = new HashMap<>();
        List<Map<String, Object>> unknown = new ArrayList<>();
        for (Map<String, Object> a : attractions) {
            String name = (String) a.get("name");
            double p = rawPrice(a);
            if (p > 0) {
                prices.put(name, p);
            } else if (!isFree(a)) {
                unknown.add(a);
            }
        }
        if (unknown.isEmpty()) return prices;
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请给出以下中国景区门票成人参考价(单位:元/人)。若该景区确认免费，价格为0。只返回JSON格式 {\"景点名\": 价格数字, ...},不确定的给合理估算。\\n");
            for (Map<String, Object> a : unknown) {
                prompt.append(" - ").append(a.get("name"))
                        .append("(").append(city)
                        .append(",").append(a.get("category"))
                        .append(",").append(a.get("level")).append(")\n");
            }
            LlmResult r = llmService.chat(prompt.toString(), List.of(), List.of(), "system");
            String content = r.getContent();
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start >= 0 && end > start) {
                JsonNode node = mapper.readTree(content.substring(start, end + 1));
                node.fields().forEachRemaining(e -> {
                    if (e.getValue().isNumber()) prices.put(e.getKey(), e.getValue().asDouble());
                });
            }
            log.info("LLM 门票估算: {}", prices);
        } catch (Exception e) {
            log.warn("LLM 门票估算失败: {}", e.getMessage());
        }
        for (Map<String, Object> a : unknown) {
            prices.putIfAbsent((String) a.get("name"), priorPrice(a));
        }
        return prices;
    }

    private String transitBetween(String city, Map<String, Object> a, Map<String, Object> b) {
        String origin = coordLngLat(a);
        String dest = coordLngLat(b);
        if (origin == null || dest == null || amapService == null) return null;
        String key = city + "|" + origin + "|" + dest;
        return transitCache.computeIfAbsent(key, k -> amapService.transitPlan(origin, dest, city));
    }

    private String coordLngLat(Map<String, Object> poi) {
        String lat = (String) poi.get("lat");
        String lng = (String) poi.get("lng");
        if (lat == null || lat.isEmpty() || lng == null || lng.isEmpty()) return null;
        return lng + "," + lat;
    }

    private double attractionPrice(Map<String, Object> poi, Map<String, Double> prices) {
        double p = rawPrice(poi);
        if (p > 0) return p;
        if (isFree(poi)) return 0;
        Double est = prices.get(poi.get("name"));
        return est != null ? est : priorPrice(poi);
    }

    private double priorPrice(Map<String, Object> poi) {
        String cat = String.valueOf(poi.get("category"));
        String lv = String.valueOf(poi.get("level"));
        if ("5".equals(lv) || "4".equals(lv)) {
            return "5".equals(lv) ? 150 : 80;
        }
        if (cat.contains("主题乐园")) return 150;
        if (cat.contains("博物馆") || cat.contains("纪念馆") || cat.contains("展览")) return 40;
        if (cat.contains("公园")) return 60;
        if (cat.contains("历史") || cat.contains("古迹")) return 70;
        if (cat.contains("山") || cat.contains("湖") || cat.contains("田园")) return 90;
        return 50;
    }

    private double rawPrice(Map<String, Object> poi) {
        Object p = poi.get("price");
        if (p == null) return 0;
        String s = p.toString().replaceAll("[^0-9.]", "");
        if (s.isEmpty() || "0".equals(s)) return 0;
        try {
            double v = Double.parseDouble(s);
            return v > 0 ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isFree(Map<String, Object> poi) {
        return Boolean.TRUE.equals(poi.get("free"));
    }

    private boolean hasMappedCategory(String interests) {
        return containsAny(interests, "自然", "山水", "风光", "风景", "户外", "森林")
                || containsAny(interests, "历史", "古迹", "人文", "文化", "陵", "寺")
                || containsAny(interests, "博物馆", "科技", "展")
                || containsAny(interests, "亲子", "动物", "小孩");
    }

    private String priceLabel(Map<String, Object> poi) {
        return isFree(poi) ? "免费" : "收费";
    }

    private String cnNum(int n) {
        return switch (n) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            default -> String.valueOf(n);
        };
    }
}
