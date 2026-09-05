package com.Myself.demo.tool;

import com.Myself.demo.bot.ToolRegistry;
import com.Myself.demo.service.AuditLogService;
import com.Myself.demo.service.TravelPlanService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TravelTool {

    private final TravelPlanService travelPlanService;
    private final AuditLogService auditLogService;
    private final ToolRegistry toolRegistry;
    private final FliggyTravelTool fliggyTravelTool;

    public TravelTool(TravelPlanService travelPlanService, AuditLogService auditLogService,
                      ToolRegistry toolRegistry, FliggyTravelTool fliggyTravelTool) {
        this.travelPlanService = travelPlanService;
        this.auditLogService = auditLogService;
        this.toolRegistry = toolRegistry;
        this.fliggyTravelTool = fliggyTravelTool;
    }

    @Tool(name = "generate_travel_guide", value = "生成全面旅游攻略:一次查询12306火车票(去程+返程)与机票(飞猪),并生成目的地攻略全部方案(方案一/二/三)")
    public String generateTravelGuide(
            @P("目的地城市") String city,
            @P("旅行天数") int days,
            @P("出发城市") String fromCity,
            @P("出发日期，yyyy-MM-dd，用户未提可留空") String date,
            @P("兴趣偏好") String interests,
            @P("系统用户ID，不需要向用户询问") String userId) {
        try {
            String from = (fromCity == null || fromCity.isBlank()) ? "出发城市" : fromCity;
            String goDate = (date == null || date.isBlank())
                    ? LocalDate.now().plusDays(1).toString() : date;
            String backDate = LocalDate.parse(goDate).plusDays(Math.max(days - 1, 0)).toString();

            StringBuilder sb = new StringBuilder();
            sb.append("🧳 ").append(city).append(days).append("日游 · 全面攻略\n\n");

            sb.append("🚄 火车票（12306实时）\n");
            if (fromCity == null || fromCity.isBlank()) {
                sb.append("  请先告诉我出发城市，再为您查询火车票与机票。\n");
            } else {
                String trainGo = queryTrain(from, city, goDate, userId);
                String trainBack = queryTrain(city, from, backDate, userId);
                if (trainGo == null && trainBack == null) {
                    sb.append("  12306余票查询暂时不可用，可稍后重试\n");
                } else {
                    sb.append("  去程 ").append(goDate).append(" ").append(from).append("→").append(city).append("\n")
                            .append(trainGo != null ? trainGo : "  暂无车次\n");
                    sb.append("  返程 ").append(backDate).append(" ").append(city).append("→").append(from).append("\n")
                            .append(trainBack != null ? trainBack : "  暂无车次\n");
                }
            }

            sb.append("\n✈️ 机票（飞猪实时）\n");
            if (fromCity == null || fromCity.isBlank()) {
                sb.append("  待补充出发城市后查询。\n");
            } else {
                String flight = queryFlight(from, city, goDate);
                if (flight != null) {
                    sb.append(flight);
                } else {
                    // 查不到具体航班（参考数据/无价格/失败）→ 整段不展示，避免误导
                    sb.append("  暂无该日期具体航班信息，建议乘坐高铁/动车，或以火车方案为准。\n");
                }
            }

            sb.append("\n🏖 目的地攻略\n");
            List<Map<String, Object>> attractions = travelPlanService.searchAttractions(city, null);
            if (attractions.isEmpty()) {
                sb.append("  未找到「").append(city).append("」的景点数据，请检查城市名称。");
            } else {
                sb.append(travelPlanService.generatePlans(city, days, interests, 0, attractions));
            }

            auditLogService.logSuccess("system", "generate_travel_guide", "guide", city,
                    days + "天 " + from + "→" + city + " " + goDate);
            return sb.toString();
        } catch (Exception e) {
            log.error("生成全面攻略失败", e);
            return "生成全面攻略失败: " + e.getMessage();
        }
    }

    private String queryTrain(String from, String to, String date, String userId) {
        try {
            String args = "{\"date\":\"" + date + "\",\"fromStation\":\"" + from
                    + "\",\"toStation\":\"" + to + "\",\"limitedNum\":5,\"sortFlag\":\"startTime\"}";
            String res = toolRegistry.execute("get-tickets", args, userId);
            if (res == null || res.isBlank()) return null;
            res = res.trim();
            if (res.contains("Error") || res.contains("不可用") || res.contains("没有查询") || res.contains("执行失败")) return null;
            res = res.replaceAll("https?://\\S+", "").replaceAll("\n{3,}", "\n\n").trim();
            return "  " + res.replace("\n", "\n  ") + "\n";
        } catch (Exception e) {
            log.warn("12306查票失败: {}→{} {}", from, to, date, e);
            return null;
        }
    }

    private String queryFlight(String from, String to, String date) {
        try {
            String q = "从" + from + "到" + to + "的机票，日期" + date + "前后";
            String res = fliggyTravelTool.search(q);
            if (res == null || res.isBlank()) return null;
            res = res.trim();
            // 仅接受含具体航班+价格的实时结果；参考数据/无价格/失败一律视为查不到
            if (res.contains("仅供参考") || res.contains("数据覆盖")
                    || res.contains("尚未安装") || res.contains("查询失败")
                    || res.contains("超时") || res.contains("未返回结果")
                    || (!res.contains("¥") && !res.contains("元"))) return null;
            res = res.replaceAll("https?://\\S+", "").replaceAll("\n{3,}", "\n\n").trim();
            return "  " + res.replace("\n", "\n  ") + "\n";
        } catch (Exception e) {
            log.warn("机票查询失败: {}→{} {}", from, to, date, e);
            return null;
        }
    }

    @Tool(name = "search_poi", value = "查询城市景点信息")
    public String searchPoi(
            @P("城市名称") String city,
            @P("景点类别") String category) {
        try {
            List<Map<String, Object>> result = travelPlanService.searchAttractions(city, category);
            if (result.isEmpty()) return "在「" + city + "」没有找到相关景点。";

            String enriched = travelPlanService.enrichAttractionsWithCoords(city, result);
            auditLogService.logSuccess("system", "search_poi", "attraction", city, "查到" + result.size() + "个景点");
            return enriched;
        } catch (Exception e) {
            log.error("searchPoi 失败", e);
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool(name = "generate_travel_plan", value = "生成旅游攻略方案")
    public String generateTravelPlan(
            @P("城市名称") String city,
            @P("旅行天数") int days,
            @P("兴趣偏好") String interests,
            @P("要排除的景点名，多个用逗号分隔，可为空") String exclude,
            @P("要生成的方案序号，1/2/3，传0或留空表示全部生成") int plan) {
        try {
            List<Map<String, Object>> attractions = travelPlanService.searchAttractions(city, null);
            if (attractions.isEmpty()) return "未找到「" + city + "」的景点数据，请检查城市名称。";

            List<Map<String, Object>> filtered = filterExcluded(attractions, exclude);
            if (filtered.isEmpty()) return "排除后没有可用景点，请减少排除项。";

            String planText = travelPlanService.generatePlans(city, days, interests, plan, filtered);
            auditLogService.logSuccess("system", "generate_travel_plan", "plan", city,
                    days + "天" + (interests != null ? " 偏好:" + interests : "") + (exclude != null ? " 排除:" + exclude : "") + " 方案:" + plan);
            return planText;
        } catch (Exception e) {
            log.error("生成攻略失败", e);
            return "生成攻略失败: " + e.getMessage();
        }
    }

    @Tool(name = "generate_full_itinerary", value = "生成全面的整体行程安排(出发交通、当地逐日安排、返程交通)，在用户选定方案并确认订票后调用")
    public String generateFullItinerary(
            @P("城市名称") String city,
            @P("旅行天数") int days,
            @P("出发城市") String fromCity,
            @P("兴趣偏好") String interests,
            @P("用户已选定的方案序号，1/2/3") int plan,
            @P("交通方式，火车或飞机") String transport) {
        try {
            List<Map<String, Object>> attractions = travelPlanService.searchAttractions(city, null);
            if (attractions.isEmpty()) return "未找到「" + city + "」的景点数据，请检查城市名称。";

            String itinerary = travelPlanService.generateFullItinerary(city, days, fromCity, interests, plan, transport, attractions);
            auditLogService.logSuccess("system", "generate_full_itinerary", "itinerary", city,
                    days + "天 方案:" + plan + " 交通:" + transport);
            return itinerary;
        } catch (Exception e) {
            log.error("生成整体行程失败", e);
            return "生成整体行程失败: " + e.getMessage();
        }
    }

    private List<Map<String, Object>> filterExcluded(List<Map<String, Object>> attractions, String exclude) {
        if (exclude == null || exclude.isBlank()) return attractions;
        String[] tokens = exclude.split("[,\\s、，]+");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> a : attractions) {
            String name = String.valueOf(a.get("name"));
            boolean excluded = false;
            for (String t : tokens) {
                if (!t.isBlank() && name.contains(t.trim())) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) result.add(a);
        }
        log.info("排除 {} 个景点: {}, 剩余 {} 个", tokens.length, exclude, result.size());
        return result;
    }
}
