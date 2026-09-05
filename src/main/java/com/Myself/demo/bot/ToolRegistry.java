package com.Myself.demo.bot;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Slf4j
@Component
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final Map<String, ToolEntry> tools = new HashMap<>();
    private volatile List<ToolFunction> cachedTools;

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        tools.clear();
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null) continue;
            for (Method method : beanType.getDeclaredMethods()) {
                Tool toolAnno = method.getAnnotation(Tool.class);
                if (toolAnno == null) continue;

                String toolName = toolAnno.name();
                if (toolName == null || toolName.isEmpty()) {
                    toolName = method.getName();
                }
                String description = toolAnno.value().length > 0
                        ? String.join(" ", toolAnno.value())
                        : method.getName();

                Parameter[] params = method.getParameters();
                List<ParamDef> paramDefs = new ArrayList<>();

                for (Parameter param : params) {
                    P pAnno = param.getAnnotation(P.class);
                    if (pAnno == null) continue;
                    String paramName = param.getName();
                    String paramDesc = pAnno.value();
                    Class<?> paramType = param.getType();
                    // Maven 未开启 -parameters 时，反射参数名会退化为 arg0/arg1。
                    // 因此系统参数还要通过 @P 的说明识别，避免把用户ID暴露给模型并导致
                    // B站等按用户隔离的工具无法取得真实调用方。
                    boolean isSystem = "userId".equals(paramName)
                            || "系统用户ID，不需要向用户询问".equals(paramDesc);

                    paramDefs.add(new ParamDef(paramName, paramDesc, paramType, isSystem));
                }

                if (tools.containsKey(toolName)) {
                    log.warn("同名工具已存在，跳过: {}", toolName);
                    continue;
                }

                tools.put(toolName, new ToolEntry(toolName, description, beanName, method, paramDefs));
                log.info("注册工具: {} ({})", toolName, beanType.getSimpleName());
            }
        }
    }

    public List<ToolFunction> buildTools() {
        if (cachedTools != null) return cachedTools;
        List<ToolFunction> result = new ArrayList<>();
        for (ToolEntry entry : tools.values()) {
            if (entry.toolFunction != null) {
                result.add(entry.toolFunction);
            } else if (entry.paramDefs != null) {
                ToolFunction tf = ToolFunction.builder()
                    .function(FunctionDefinition.builder()
                            .name(entry.name)
                            .description(entry.description)
                            .parameters(buildParams(entry))
                            .build())
                    .build();
                result.add(tf);
            }
        }
        cachedTools = result;
        return cachedTools;
    }

    private static final Map<String, List<String>> DOMAIN_KEYWORDS;
    private static final Map<String, List<String>> DOMAIN_TOOLS;
    private static final Set<String> ALWAYS_INCLUDED;

    static {
        DOMAIN_KEYWORDS = new LinkedHashMap<>();
        DOMAIN_TOOLS = new LinkedHashMap<>();

        DOMAIN_KEYWORDS.put("天气", Arrays.asList("天气", "空气质量", "温度", "湿度", "风力", "下雨", "下雪", "晴", "多云", "阴天"));
        DOMAIN_TOOLS.put("天气", Arrays.asList("weather", "air_quality"));

        DOMAIN_KEYWORDS.put("搜索", Arrays.asList("搜索", "百度", "谷歌", "搜一下", "查一下", "帮我找", "查找"));
        DOMAIN_TOOLS.put("搜索", Arrays.asList("baidu_search", "google_search", "qr_code"));

        DOMAIN_KEYWORDS.put("图片", Arrays.asList("图片", "图像", "画画", "画图", "生成图", "修图", "图片处理", "识图", "改图"));
        DOMAIN_TOOLS.put("图片", Arrays.asList("generate_image", "transform_image", "re_examine_image"));

        DOMAIN_KEYWORDS.put("文件", Arrays.asList("文件", "翻译文件", "提取", "摘要", "保存文件", "导出"));
        DOMAIN_TOOLS.put("文件", Arrays.asList("translate_file", "extract_from_file", "search_in_file", "export_file_summary", "save_as_file"));

        DOMAIN_KEYWORDS.put("日历", Arrays.asList("日历", "日期", "星期", "月份", "年份", "节假日", "农历", "节日"));
        DOMAIN_TOOLS.put("日历", Arrays.asList("calendar_month", "calendar_date", "calendar_year"));

        DOMAIN_KEYWORDS.put("八字", Arrays.asList("八字", "算命", "运程", "命理", "五行", "生肖", "运势", "流年", "命盘", "起名", "取名"));
        DOMAIN_TOOLS.put("八字", Arrays.asList("eight_characters", "annual_fortune", "element_preference"));

        DOMAIN_KEYWORDS.put("塔罗", Arrays.asList("塔罗", "塔罗牌", "占卜"));
        DOMAIN_TOOLS.put("塔罗", Arrays.asList("tarot"));

        DOMAIN_KEYWORDS.put("健康", Arrays.asList("健康", "身体", "体检", "医疗", "医药", "医院", "症状", "药品", "用药", "营养", "卡路里", "脂肪",
                "身体指标", "血压", "血糖", "药房", "健康报告", "体检报告"));
        DOMAIN_TOOLS.put("健康", Arrays.asList("body_fat_rate", "food_nutrition", "list_nutrition_foods", "health_tip",
                "health_overview", "record_profile_fact", "add_medication", "record_vital", "log_medication",
                "find_pharmacy", "symptom_screening", "interpret_health_report", "save_health_report",
                "interpret_latest_health_report", "open_health_exam", "create_health_plan",
                "daily_health_briefing", "schedule_health_briefing",
                "record_meal", "analyze_meal", "today_diet_summary", "rag_search"));

        DOMAIN_KEYWORDS.put("菜谱", Arrays.asList("菜谱", "食谱", "做饭", "烹饪", "炒菜", "菜单"));
        DOMAIN_TOOLS.put("菜谱", Arrays.asList("caipu"));

        DOMAIN_KEYWORDS.put("快递", Arrays.asList("快递", "包裹", "物流", "运单", "配送"));
        DOMAIN_TOOLS.put("快递", Arrays.asList("query_delivery"));

        DOMAIN_KEYWORDS.put("旅游", Arrays.asList("旅游", "旅行", "景点", "攻略", "酒店", "机票", "火车票", "订票", "订单", "行程", "门票", "目的地", "出行", "出发"));
        // 注意：不放 get_ticket_buy_link / fliggy_travel_search —— 阶段1(仅命中旅游域)不能生成购票链接；
        // 只有用户明确提“订票/票/车次”(同时命中12306域)时才放行全部工具，由 LLM 生成订票链接。
        DOMAIN_TOOLS.put("旅游", Arrays.asList("generate_travel_guide", "search_poi", "generate_travel_plan", "generate_full_itinerary",
                "create_order", "query_order", "cancel_order", "get_user_location"));

        DOMAIN_KEYWORDS.put("语音", Arrays.asList("语音", "声音", "配音", "音色", "声优"));
        DOMAIN_TOOLS.put("语音", Arrays.asList("switch_voice", "enable_voice", "disable_voice", "list_voice_types"));

        DOMAIN_KEYWORDS.put("待办", Arrays.asList("待办", "待办事项", "备忘录", "提醒事项", "任务"));
        DOMAIN_TOOLS.put("待办", Arrays.asList("add_todo", "list_todos", "delete_todo", "mark_todo_done"));

        DOMAIN_KEYWORDS.put("提醒", Arrays.asList("提醒", "闹钟", "定时"));
        DOMAIN_TOOLS.put("提醒", Arrays.asList("create_reminder", "list_reminders", "cancel_reminder"));

        DOMAIN_KEYWORDS.put("计算", Arrays.asList("计算", "算数", "数学", "公式", "科学计算", "计算器"));
        DOMAIN_TOOLS.put("计算", Arrays.asList("sci_calc"));

        DOMAIN_KEYWORDS.put("二维码", Arrays.asList("二维码", "扫码", "条形码", "生成二维码", "解码"));
        DOMAIN_TOOLS.put("二维码", Arrays.asList("qr_code", "decode_qr_code"));

        DOMAIN_KEYWORDS.put("时间", Arrays.asList("时间", "时区", "世界时间", "当地时间", "几点"));
        DOMAIN_TOOLS.put("时间", Arrays.asList("world_time", "local_time"));

        DOMAIN_KEYWORDS.put("热搜", Arrays.asList("热搜", "新闻", "热点", "热门", "AI新闻", "ai新闻"));
        DOMAIN_TOOLS.put("热搜", Arrays.asList("network_hot", "ai_news"));

        DOMAIN_KEYWORDS.put("位置", Arrays.asList("位置", "定位", "导航", "在哪", "附近"));
        DOMAIN_TOOLS.put("位置", Arrays.asList("get_user_location"));

        DOMAIN_KEYWORDS.put("B站", Arrays.asList("B站", "bilibili", "哔哩哔哩", "BiliBili", "视频", "收藏", "字幕", "UP主", "up主"));
        DOMAIN_TOOLS.put("B站", Arrays.asList("bili_login_qr", "bili_login_confirm", "bili_fav_folders",
                "bili_fav_videos", "bili_video_subtitle"));

        DOMAIN_KEYWORDS.put("12306", Arrays.asList("12306", "火车票", "车票", "查票", "买票", "订票", "余票", "列车", "高铁", "动车", "车站", "车次", "票"));
        // 注意：12306 工具名由 McpIntegration 运行时注册（12306-mcp 包：get-tickets / get-station-code-by-names / get-current-date），如工具名变化需同步更新此列表
        DOMAIN_TOOLS.put("12306", Arrays.asList("get-tickets", "get-interline-tickets", "get-station-code-by-names", "get-station-code-of-citys", "get-current-date"));

        ALWAYS_INCLUDED = new LinkedHashSet<>(Arrays.asList(
                "help", "status", "version", "remember_fact", "rag_search",
                "query-meals", "query-meal-detail", "calculate-price",
                "query-nearby-stores", "query-my-account", "now-time-info"));
    }

    public List<ToolFunction> filterToolsByInput(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return buildTools();
        }

        Set<String> matchedDomains = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : DOMAIN_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (userInput.contains(keyword)) {
                    matchedDomains.add(entry.getKey());
                    break;
                }
            }
        }

        if (matchedDomains.isEmpty()) {
            return buildTools();
        }

        // 12306/MCP 工具名运行时才确定，匹配到该域时不过滤
        if (matchedDomains.contains("12306") || matchedDomains.contains("B站")) {
            return buildTools();
        }

        Set<String> allowedTools = new LinkedHashSet<>(ALWAYS_INCLUDED);
        for (String domain : matchedDomains) {
            List<String> tools = DOMAIN_TOOLS.get(domain);
            if (tools != null) {
                allowedTools.addAll(tools);
            }
        }

        // 用户明确要"搜索/查一下"具体内容时,排除热搜榜单类工具,强制走 baidu/google 搜索
        if (matchedDomains.contains("搜索")) {
            allowedTools.remove("network_hot");
            allowedTools.remove("ai_news");
        }

        // 命中热搜域但用户问的是具体资讯(如"XX有什么新闻/消息/动态"),并非榜单意图(没提热搜/热门/热点)时,
        // 同样排除榜单工具并加入搜索工具,避免 LLM 拿热搜榜单答具体内容
        if (matchedDomains.contains("热搜")) {
            boolean wantsList = containsAnyKeyword(userInput, "热搜", "热门", "热点", "热榜", "头条", "榜单", "排行榜", "实时");
            boolean asksSpecific = containsAnyKeyword(userInput, "新闻", "消息", "资讯", "动态", "事件", "报道", "近况", "圈");
            if (!wantsList && asksSpecific) {
                allowedTools.remove("network_hot");
                allowedTools.remove("ai_news");
                allowedTools.add("baidu_search");
                allowedTools.add("google_search");
            }
        }

        List<ToolFunction> allTools = buildTools();
        List<ToolFunction> filtered = new ArrayList<>();
        for (ToolFunction tf : allTools) {
            if (allowedTools.contains(tf.getFunction().getName())) {
                filtered.add(tf);
            }
        }

        log.info("域过滤: 匹配域={}, 工具数 {} -> {}", matchedDomains, allTools.size(), filtered.size());
        return filtered;
    }

    private boolean containsAnyKeyword(String input, String... keywords) {
        if (input == null) return false;
        for (String k : keywords) {
            if (input.contains(k)) return true;
        }
        return false;
    }

    private JsonObject buildParams(ToolEntry entry) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();

        for (ParamDef pd : entry.paramDefs) {
            if (pd.systemParam) continue;

            JsonObject prop = new JsonObject();
            if (pd.type == String.class) {
                prop.addProperty("type", "string");
            } else if (pd.type == int.class || pd.type == Integer.class) {
                prop.addProperty("type", "integer");
            } else if (pd.type == double.class || pd.type == Double.class) {
                prop.addProperty("type", "number");
            } else if (pd.type == boolean.class || pd.type == Boolean.class) {
                prop.addProperty("type", "boolean");
            } else {
                prop.addProperty("type", "string");
            }
            prop.addProperty("description", pd.description);
            properties.add(pd.name, prop);
            required.add(new JsonPrimitive(pd.name));
        }

        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    public void registerExternal(String toolName, ToolFunction toolFunction,
                                   TriFunction<String, String, String, String> executor) {
        tools.put(toolName, new ToolEntry(toolName, toolFunction.getFunction().getDescription(), executor, toolFunction));
        cachedTools = null;
        log.info("外部工具已注册: {}", toolName);
    }

    public String execute(String toolName, String llmArgs, String userId) {
        ToolEntry entry = tools.get(toolName);
        if (entry == null) return null;

        // 外部工具（MCP等）
        if (entry.executor != null) {
            try {
                return entry.executor.apply(toolName, llmArgs, userId);
            } catch (Exception e) {
                log.error("外部工具执行失败: {}", toolName, e);
                return "工具执行失败";
            }
        }

        // @Tool 反射执行
        try {
            List<Object> callArgs = new ArrayList<>();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode argsNode = parseArgs(mapper, llmArgs);

            for (ParamDef pd : entry.paramDefs) {
                if (pd.systemParam) {
                    callArgs.add(userId);
                } else {
                    com.fasterxml.jackson.databind.JsonNode node = argsNode.get(pd.name);
                    if (pd.type == String.class) {
                        callArgs.add(node != null && !node.isNull() ? node.asText() : "");
                    } else if (pd.type == int.class || pd.type == Integer.class) {
                        callArgs.add(node != null && !node.isNull() ? extractNumber(node.asText(), 0) : 0);
                    } else if (pd.type == double.class || pd.type == Double.class) {
                        callArgs.add(node != null && !node.isNull() ? extractDouble(node.asText(), 0.0) : 0.0);
                    } else if (pd.type == boolean.class || pd.type == Boolean.class) {
                        callArgs.add(node != null && !node.isNull() ? node.asBoolean() : false);
                    } else {
                        callArgs.add(node != null && !node.isNull() ? node.asText() : "");
                    }
                }
            }

            Object bean = applicationContext.getBean(entry.beanName);
            return (String) entry.method.invoke(bean, callArgs.toArray());
        } catch (Exception e) {
            log.error("工具执行失败: {}", toolName, e);
            return "工具执行失败";
        }
    }

    /** LLM 参数容错解析：数值带中文单位（如 "remaining": 30片）、单引号、尾逗号等常见非严格 JSON。 */
    private com.fasterxml.jackson.databind.JsonNode parseArgs(com.fasterxml.jackson.databind.ObjectMapper mapper, String llmArgs) throws Exception {
        String raw = llmArgs == null ? "{}" : llmArgs;
        try {
            return mapper.readTree(raw);
        } catch (Exception ignored) {
            String fixed = raw
                    .replaceAll("(:\\s*\\d+)[\\u4e00-\\u9fa5A-Za-z]+(?=\\s*[,}])", "$1")
                    .replace('\'', '"')
                    .replaceAll(",\\s*([}\\]])", "$1")
                    .replaceAll("(?<=[{,])\\s*([\\u4e00-\\u9fa5A-Za-z_]+)\\s*:", "\"$1\":");
            return mapper.readTree(fixed);
        }
    }

    private int extractNumber(String text, int defaultValue) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[-+]?\\d+").matcher(text);
            return m.find() ? Integer.parseInt(m.group()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double extractDouble(String text, double defaultValue) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(text);
            return m.find() ? Double.parseDouble(m.group()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private static class ToolEntry {
        final String name;
        final String description;
        final String beanName;
        final Method method;
        final List<ParamDef> paramDefs;
        final TriFunction<String, String, String, String> executor;
        final ToolFunction toolFunction;

        ToolEntry(String name, String description, String beanName, Method method, List<ParamDef> paramDefs) {
            this.name = name;
            this.description = description;
            this.beanName = beanName;
            this.method = method;
            this.paramDefs = paramDefs;
            this.executor = null;
            this.toolFunction = null;
        }

        ToolEntry(String name, String description, TriFunction<String, String, String, String> executor, ToolFunction toolFunction) {
            this.name = name;
            this.description = description;
            this.beanName = null;
            this.method = null;
            this.paramDefs = null;
            this.executor = executor;
            this.toolFunction = toolFunction;
        }
    }

    private static class ParamDef {
        final String name;
        final String description;
        final Class<?> type;
        final boolean systemParam;

        ParamDef(String name, String description, Class<?> type, boolean systemParam) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.systemParam = systemParam;
        }
    }
}
