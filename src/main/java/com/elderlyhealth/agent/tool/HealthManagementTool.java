package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.AmapService;
import com.elderlyhealth.agent.service.HealthManagementService;
import com.elderlyhealth.agent.service.PharmacyResponseFormatter;
import com.elderlyhealth.agent.service.ReminderConfigService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WeChat-native health management commands. */
@Component
public class HealthManagementTool {
    private final HealthManagementService health;
    private final AmapService amap;
    private final PharmacyResponseFormatter pharmacyFormatter;
    private final ReminderConfigService reminders;
    private final String healthExamUrl;
    private static final Pattern TIME = Pattern.compile("(?:^|[,，\\s])([01]?\\d|2[0-3]):([0-5]\\d)(?:$|[,，\\s])");

    public HealthManagementTool(HealthManagementService health, AmapService amap, ReminderConfigService reminders,
                                PharmacyResponseFormatter pharmacyFormatter,
                                @Value("${health.exam.url:}") String healthExamUrl) {
        this.health = health;
        this.amap = amap;
        this.reminders = reminders;
        this.pharmacyFormatter = pharmacyFormatter;
        this.healthExamUrl = healthExamUrl == null ? "" : healthExamUrl.trim();
    }

    @Tool(name = "health_overview", value = "查看用户的个人电子健康档案，包括登记用药、最近健康指标和服药记录。")
    public String overview(@P("用户ID，系统自动填充") String userId) {
        return health.richOverview(userId);
    }

    @Tool(name = "record_profile_fact", value = "记录健康档案中的基础信息、生活习惯、过敏史、既往病史、家族史、运动和饮食等信息。")
    public String recordProfileFact(@P("档案字段，例如睡眠、身高、年龄、过敏史、运动习惯") String key,
                                    @P("字段值") String value, @P("用户ID，系统自动填充") String userId) {
        return health.recordProfileFact(userId, key, value);
    }

    @Tool(name = "add_medication", value = "登记药品和多时段服药计划，持久化到个人电子用药档案；余量较低时提醒用户补药。")
    public String addMedication(@P("药品名称") String name, @P("每次用量") String dosage,
                                @P("服药时段，例如早晚或 08:00,20:00") String schedule,
                                @P("剩余数量") int remaining, @P("用户ID，系统自动填充") String userId) {
        String result = health.addMedication(userId, name, dosage, schedule, remaining);
        Matcher matcher = TIME.matcher((schedule == null ? "" : schedule) + " ");
        int count = 0;
        while (matcher.find()) {
            reminders.add(userId, null, "服用 " + name + "（" + dosage + "），请按医嘱服药。", Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            count++;
        }
        return count == 0 ? result : result + " 已按时段创建 " + count + " 个每日服药提醒。";
    }

    @Tool(name = "record_vital", value = "记录血压、血糖、体重、体温等健康指标，长期留存并用于健康趋势回顾。")
    public String recordVital(@P("指标名称，例如血压或空腹血糖") String type, @P("指标值") String value,
                              @P("测量时间，仅当用户明确说出具体日期时间时才填写，例如「今天早上」「8月3日 20:30」，用户没说时间就留空，绝不要编造日期") String measuredAt,
                              @P("用户ID，系统自动填充") String userId) {
        return health.recordVital(userId, type, value, measuredAt);
    }

    @Tool(name = "log_medication", value = "记录用户已完成一次服药。处理「我吃了XX」「刚吃了XX」「服用XX」等服药打卡。药品名称不需要提前登记，自动补充到用药档案。")
    public String logMedication(@P("药品名称，例如阿莫西林、布洛芬、头孢克肟") String name, @P("服药时间，可留空") String takenAt,
                                @P("用户ID，系统自动填充") String userId) {
        return health.logMedication(userId, name, takenAt);
    }

    public String logMedication(String name, int quantity, String userId) {
        return health.logMedication(userId, name, quantity, "");
    }

    public String clearAllHealthData(String userId) {
        return health.clearAllHealthData(userId);
    }

    public String queryRemaining(String userId, String drugName) {
        return health.queryRemaining(userId, drugName);
    }

    @Tool(name = "find_pharmacy", value = "查询附近药店")
    public String findPharmacy(@P("城市") String city, @P("位置或地标") String location) {
        String keyword = (location != null && !location.isEmpty()) ? location + " 药店" : "药店";
        List<Map<String, Object>> pois = amap.searchPoi(city, keyword);
        if (!pois.isEmpty()) {
            StringBuilder out = new StringBuilder("【附近药店】\n");
            pois.stream().limit(5).forEach(p -> {
                out.append("- ").append(p.get("name")).append("：").append(p.get("address"));
                Object distance = p.get("distance");
                if (distance != null && !distance.toString().isBlank()) out.append("（约 ").append(distance).append(" 米）");
                out.append("\n");
            });
            return out.toString();
        }
        return "暂未查到附近药店，请确认城市名称或稍后重试。";
    }

    @Tool(name = "symptom_screening", value = "语音症状智能咨询的安全初筛入口。结合症状给出分级就医与护理建议，明确不能替代医生诊断。")
    public String symptomScreening(@P("症状、持续时间和严重程度") String symptoms) {
        return health.screenSymptoms(symptoms);
    }

    @Tool(name = "interpret_health_report", value = "归集体检报告文字并做风险关键词初筛，提示复核和就医方向，不进行确诊。")
    public String interpretReport(@P("体检报告文字或图片识别出的文字") String reportText) {
        return health.interpretReport(reportText);
    }

    @Tool(name = "save_health_report", value = "把体检报告文字归集到当前用户的个人健康档案，并返回非诊断性的异常关键词和复查提示。")
    public String saveHealthReport(@P("体检报告文字或图片识别出的文字") String reportText,
                                   @P("用户ID，系统自动填充") String userId) {
        return health.saveHealthReport(userId, reportText);
    }

    @Tool(name = "interpret_latest_health_report", value = "解读当前用户健康档案中最近一次保存的体检报告，给出复查和就医提示，不进行确诊。")
    public String interpretLatestReport(@P("用户ID，系统自动填充") String userId) {
        return health.interpretLatestReport(userId);
    }

    @Tool(name = "open_health_exam", value = "提供已配置的线上体检预约入口。只返回项目配置的正式入口，不编造链接。")
    public String openHealthExam() {
        if (healthExamUrl.isBlank()) {
            return "线上体检入口尚未配置，请联系管理员设置 health.exam.url 后再试。";
        }
        return "线上体检预约\n\n入口：" + healthExamUrl
                + "\n\n预约前请核对体检机构、套餐内容、适用人群和退款规则。";
    }

    @Tool(name = "create_health_plan", value = "根据病症特征与健康目标生成饮食、食谱方向和运动干预建议。")
    public String createPlan(@P("病症特征或慢病情况") String conditions, @P("健康目标，例如控糖或减重") String goal,
                             @P("用户ID，系统自动填充") String userId) {
        return health.savePlan(userId, conditions, goal, health.plan(conditions, goal));
    }

    @Tool(name = "daily_health_briefing", value = "生成每日健康陪伴简报，整合用药清单、季节天气健康提醒和慢病科普方向，可配合每日提醒推送。")
    public String dailyBriefing(@P("城市") String city, @P("用户ID，系统自动填充") String userId) {
        return health.dailyBriefingWithWeather(userId, city);
    }

    public String findLastLoggedMedicationName(String userId) {
        return health.findLastLoggedMedicationName(userId);
    }

    @Tool(name = "schedule_health_briefing", value = "设置每日健康陪伴推送，定时发送用药清单和生活方式健康提醒。")
    public String scheduleBriefing(@P("提醒时间，HH:mm，例如 08:00") String time,
                                    @P("城市") String city, @P("用户ID，系统自动填充") String userId) {
        try {
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return "时间应为 HH:mm，例如 08:00。";
            reminders.add(userId, "daily_health_briefing", "{\"city\":\"" + (city == null ? "" : city.replace("\"", "")) + "\"}", hour, minute);
            return String.format("已设置每日 %02d:%02d 健康简报推送。", hour, minute);
        } catch (Exception e) {
            return "时间应为 HH:mm，例如 08:00。";
        }
    }

    @Tool(name = "record_meal", value = "记录用户一餐的饮食情况，包括食物名称、估算热量、餐别和备注。用户发送食物照片或描述吃了什么后调用。")
    public String recordMeal(@P("食物列表和估算分量，如：白米饭200g、红烧肉100g、炒青菜80g") String foods,
                             @P("估算总热量（千卡），如 650") int estimatedCalories,
                             @P("餐别：早餐、午餐、晚餐、加餐") String mealType,
                             @P("备注或营养建议，可留空") String note,
                             @P("用户ID，系统自动填充") String userId) {
        return health.recordMeal(userId, foods, estimatedCalories, mealType, note);
    }

    @Tool(name = "today_diet_summary", value = "查看用户今天的饮食记录和总摄入热量。")
    public String todayDietSummary(@P("用户ID，系统自动填充") String userId) {
        String summary = health.dailyDietarySummary(userId);
        String profileHint = "";
        try {
            Map<String, Object> data = health.record(userId);
            Object profile = data.get("profile");
            if (profile instanceof Map && !((Map<?, ?>) profile).isEmpty()) {
                profileHint += "\n\n体质参考\n";
                for (Map.Entry<?, ?> e : ((Map<?, ?>) profile).entrySet()) {
                    profileHint += "• " + e.getKey() + "：" + e.getValue() + "\n";
                }
                profileHint += "请结合以上体质信息，在用户查看饮食记录时给出个性化改进建议。";
            }
        } catch (Exception ignored) { }
        return summary + profileHint;
    }

    @Tool(name = "analyze_meal", value = "分析用户一餐的饮食，结合健康档案和知识库给出个性化饮食建议，并自动记录到当天健康档案。当用户发送食物照片时，先调用识图识别食物，再根据识别结果调用本工具。")
    public String analyzeMeal(@P("食物分析结果，包含食物名称、分量和估算热量") String foodAnalysis,
                              @P("餐别：早餐、午餐、晚餐、加餐") String mealType,
                              @P("用户ID，系统自动填充") String userId) {
        Map<String, Object> data = health.record(userId);
        Object profile = data.get("profile");
        int totalCal = extractTotalCalories(foodAnalysis);

        String foods = foodAnalysis.length() > 200 ? foodAnalysis.substring(0, 200) + "..." : foodAnalysis;
        String recordMsg = health.recordMeal(userId, foods, totalCal, mealType, "自动记录");

        StringBuilder advice = new StringBuilder(recordMsg);
        advice.append("\n\n今日已摄入：约").append(health.todayCalories(userId)).append("千卡");

        if (profile instanceof Map && !((Map<?, ?>) profile).isEmpty()) {
            advice.append("\n\n个性化建议");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) profile).entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase();
                String v = String.valueOf(e.getValue());
                if (k.contains("血压") || k.contains("高血压")) {
                    advice.append("\n• 你有高血压，注意控制盐分摄入，避免过咸和腌制食品。");
                } else if (k.contains("血糖") || k.contains("糖尿病")) {
                    advice.append("\n• 你有血糖问题，注意控制碳水化合物总量，优先选择低GI食物。");
                } else if (k.contains("体重") || k.contains("减重")) {
                    advice.append("\n• 你正在管理体重，建议控制总热量摄入，增加蔬菜比例。");
                } else if (k.contains("过敏")) {
                    advice.append("\n• 注意避免过敏源：").append(v);
                }
            }
        }

        String today = java.time.LocalDate.now().toString();
        advice.append("\n\n").append(today).append(" 全部饮食可在健康档案中查看。");

        return advice.toString();
    }

    private static final Pattern CAL_TOTAL = Pattern.compile("总(?:估算)?(?:热量|卡路里)[^0-9]{0,6}(\\d+)\\s*(?:千卡|大卡)");
    private static final Pattern CAL_UNIT = Pattern.compile("(\\d+)\\s*(?:千卡|大卡)");

    private int extractTotalCalories(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            Matcher total = CAL_TOTAL.matcher(text);
            if (total.find()) {
                return Integer.parseInt(total.group(1));
            }
            Matcher unit = CAL_UNIT.matcher(text);
            int sum = 0;
            int max = 0;
            while (unit.find()) {
                int v = Integer.parseInt(unit.group(1));
                sum += v;
                max = Math.max(max, v);
            }
            return max > 0 ? max : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
