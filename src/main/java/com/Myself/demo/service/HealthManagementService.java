package com.Myself.demo.service;

import com.Myself.demo.entity.HealthVitalRecord;
import com.Myself.demo.mapper.HealthVitalRecordMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/** Lightweight, user-scoped health records used by the WeChat workflow. */
@Slf4j
@Service
public class HealthManagementService {
    private final HealthVitalRecordMapper vitalRecordMapper;
    private final WeatherService weatherService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Map<String, Object>> records = new ConcurrentHashMap<>();
    private final Path dataDir = Path.of("health-data");

    public HealthManagementService(HealthVitalRecordMapper vitalRecordMapper, WeatherService weatherService) {
        this.vitalRecordMapper = vitalRecordMapper;
        this.weatherService = weatherService;
    }

    public Map<String, Object> record(String userId) {
        return records.computeIfAbsent(userId, this::load);
    }

    public synchronized String addMedication(String userId, String name, String dosage,
                                              String schedule, int remaining) {
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> meds = list(data, "medications");
        Map<String, Object> med = meds.stream()
                .filter(item -> name != null && name.equalsIgnoreCase(String.valueOf(item.get("name"))))
                .findFirst()
                .orElseGet(() -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    meds.add(created);
                    return created;
                });
        med.put("name", name);
        med.put("dosage", dosage);
        med.put("schedule", schedule);
        med.put("remaining", Math.max(0, remaining));
        med.put("updatedAt", now());
        save(userId, data);
        String warning = remaining <= 5 ? "\n\n余量预警：当前仅剩 " + Math.max(0, remaining) + "，请提前补充。" : "";
        return "用药档案已更新\n\n"
                + "药品：" + safe(name, "未填写") + "\n"
                + "每次用量：" + safe(dosage, "按医嘱") + "\n"
                + "服药时间：" + safe(schedule, "未设置") + "\n"
                + "当前余量：" + Math.max(0, remaining) + warning
                + "\n\n请按医生或药师指导用药，不要自行调整剂量或停药。";
    }

    public synchronized String recordVital(String userId, String type, String value, String measuredAt) {
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> vitals = list(data, "vitals");
        LocalDateTime parsedAt = parseDateTime(measuredAt);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("value", value);
        item.put("measuredAt", parsedAt.format(DAILY_FMT));
        vitals.add(item);
        save(userId, data);
        CompletableFuture.runAsync(() -> {
            try {
                HealthVitalRecord dbRecord = new HealthVitalRecord();
                dbRecord.setUserId(userId);
                dbRecord.setType(type);
                dbRecord.setValue(value);
                dbRecord.setMeasuredAt(parsedAt);
                vitalRecordMapper.insert(dbRecord);
            } catch (Exception e) {
                log.warn("Health vital DB write failed; JSON backup retained. userId={}, type={}", userId, type, e);
            }
        });
        return "已记录" + type + "：" + value + "。如出现持续异常或不适，请及时就医。";
    }

    private LocalDateTime parseDateTime(String measuredAt) {
        if (measuredAt == null || measuredAt.isBlank()) return LocalDateTime.now();
        LocalDateTime now = LocalDateTime.now();
        try {
            String s = measuredAt.trim();
            LocalDateTime parsed = null;
            if (s.matches(".*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")) {
                java.util.regex.Matcher d = java.util.regex.Pattern
                        .compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:[ T]?(\\d{1,2}):(\\d{2}))?")
                        .matcher(s);
                if (d.find()) {
                    LocalDateTime base = LocalDateTime.of(
                            Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3)),
                            d.group(4) != null ? Integer.parseInt(d.group(4)) : 0, d.group(5) != null ? Integer.parseInt(d.group(5)) : 0);
                    long days = java.time.Duration.between(base.toLocalDate().atStartOfDay(), now.toLocalDate().atStartOfDay()).toDays();
                    if (Math.abs(days) <= 1) parsed = base;
                }
            } else if (s.contains("今天") || s.contains("今早") || s.contains("上午") || s.contains("早晨") || s.contains("早上")) {
                parsed = parseTimeOfDay(s, now);
            } else if (s.contains("昨天")) {
                parsed = parseTimeOfDay(s, now.minusDays(1));
            }
            if (parsed != null) return parsed;
            return now;
        } catch (Exception e) {
            return now;
        }
    }

    private LocalDateTime parseTimeOfDay(String s, LocalDateTime baseDate) {
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(s);
        if (t.find()) {
            try {
                return baseDate.toLocalDate().atTime(
                        Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)));
            } catch (Exception ignored) { }
        }
        if (s.contains("晚")) return baseDate.toLocalDate().atTime(20, 0);
        if (s.contains("中午")) return baseDate.toLocalDate().atTime(12, 0);
        if (s.contains("上午") || s.contains("早晨") || s.contains("早上") || s.contains("今早")) return baseDate.toLocalDate().atTime(8, 0);
        return baseDate.toLocalDate().atStartOfDay();
    }

    public synchronized String logMedication(String userId, String name, String takenAt) {
        return logMedication(userId, name, 1, takenAt);
    }

    public synchronized String logMedication(String userId, String name, int quantity, String takenAt) {
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> logs = list(data, "medicationLogs");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("takenAt", takenAt == null || takenAt.isBlank() ? now() : takenAt);
        if (quantity > 1) item.put("quantity", quantity);
        logs.add(item);
        Map<String, Object> medication = list(data, "medications").stream()
                .filter(med -> name != null && name.equalsIgnoreCase(String.valueOf(med.get("name"))))
                .findFirst().orElse(null);
        Integer remaining = null;
        if (medication != null) {
            remaining = Math.max(0, intValue(medication.get("remaining")) - quantity);
            medication.put("remaining", remaining);
            medication.put("updatedAt", now());
            Object curDosage = medication.get("dosage");
            if (curDosage == null || "未设置".equals(curDosage)) {
                medication.put("dosage", quantity + "粒");
            }
        } else if (name != null && !name.isEmpty() && !"未指定药物".equals(name)) {
            medication = new LinkedHashMap<>();
            medication.put("name", name);
            medication.put("dosage", quantity + "粒");
            medication.put("schedule", "未设置");
            medication.put("remaining", 0);
            medication.put("updatedAt", now());
            list(data, "medications").add(medication);
        }
        save(userId, data);
        String result = "服药打卡成功\n\n药品：" + safe(name, "未填写")
                + "\n时间：" + item.get("takenAt");
        if (quantity > 1) result += "\n数量：" + quantity + "粒";
        if (remaining != null) result += "\n剩余：" + remaining;
        if (remaining != null && remaining <= 5) result += "\n\n余量预警：请提前补药。";
        if (medication != null && remaining == null) result += "\n\n" + name + "未在用药档案中登记，已自动加入名录。可发送「登记用药」填写用量和时段。";
        return result;
    }

    public String overview(String userId) {
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> meds = list(data, "medications");
        List<Map<String, Object>> vitals = list(data, "vitals");
        StringBuilder out = new StringBuilder("\u4e2a\u4eba\u5065\u5eb7\u6863\u6848\n\n"
                + "\u8bf4\u660e\uff1a\u6570\u636e\u6309\u7528\u6237\u9694\u79bb\u4fdd\u5b58\uff0c\u4ec5\u4f5c\u4e2a\u4eba\u5065\u5eb7\u8bb0\u5f55\u3002\n");
        out.append("\n\u7528\u836f\u6863\u6848\uff08").append(meds.size()).append(" \u9879\uff09\n");
        for (Map<String, Object> med : meds) {
            out.append("\u2022 ").append(med.get("name")).append("\uff1a").append(med.get("remaining"));
            try {
                if (Integer.parseInt(String.valueOf(med.get("remaining"))) <= 5) out.append(" [\u9700\u8865\u836f]");
            } catch (NumberFormatException ignored) { }
            out.append("\n");
        }
        if (!vitals.isEmpty()) {
            Map<String, Object> latest = vitals.get(vitals.size() - 1);
            out.append("\n\u8fd1\u671f\u6307\u6807\n\u2022 ").append(latest.get("type")).append("\uff1a").append(latest.get("value")).append("\n");
        }
        return out.toString();
    }

    public String richOverview(String userId) {
        Map<String, Object> data = record(userId);
        StringBuilder out = new StringBuilder(HealthTextFormatter.overview(list(data, "medications"), list(data, "vitals")));
        Object profile = data.get("profile");
        if (profile instanceof Map<?, ?> facts && !facts.isEmpty()) {
            out.append("\n\n\u57fa\u7840\u4fe1\u606f\u4e0e\u751f\u6d3b\u4e60\u60ef\n");
            facts.forEach((key, value) -> out.append("• ").append(key).append("：").append(value).append("\n"));
        }
        List<Map<String, Object>> reports = list(data, "healthReports");
        List<Map<String, Object>> plans = list(data, "healthPlans");
        List<Map<String, Object>> medicationLogs = list(data, "medicationLogs");
        List<Map<String, Object>> mealLogs = list(data, "mealLogs");
        out.append("\n体检报告：").append(reports.size()).append(" 份")
                .append("  |  健康方案：").append(plans.size()).append(" 份")
                .append("  |  服药记录：").append(medicationLogs.size()).append(" 次");
        if (!mealLogs.isEmpty()) {
            String todayStr = LocalDate.now().toString();
            long todayCount = mealLogs.stream().filter(m -> todayStr.equals(String.valueOf(m.get("date")))).count();
            int todayCal = todayCalories(userId);
            out.append("  |  今日饮食：").append(todayCount).append(" 餐/约").append(todayCal).append("千卡");
        }
        if (!reports.isEmpty()) {
            Map<String, Object> latest = reports.get(reports.size() - 1);
            out.append("\n最近报告：").append(summary(latest.get("text"), 48))
                    .append("（").append(latest.get("savedAt")).append("）");
        }
        if (!plans.isEmpty()) {
            Map<String, Object> latest = plans.get(plans.size() - 1);
            out.append("\n最近目标：").append(safe(String.valueOf(latest.get("goal")), "保持健康"));
        }
        return out.toString();
    }

    public String queryRemaining(String userId, String drugName) {
        Map<String, Object> data = record(userId);
        for (Map<String, Object> med : list(data, "medications")) {
            if (drugName.equalsIgnoreCase(String.valueOf(med.get("name")))) {
                int remaining = intValue(med.get("remaining"));
                String dosage = String.valueOf(med.getOrDefault("dosage", ""));
                StringBuilder r = new StringBuilder(drugName + "\u5269\u4f59\u60c5\u51b5\n\n");
                r.append("\u2022 \u5269\u4f59\u6570\u91cf\uff1a").append(remaining);
                if (!dosage.isEmpty() && !"null".equals(dosage) && !"\u672a\u8bbe\u7f6e".equals(dosage)) {
                    r.append("\n\u2022 \u6bcf\u6b21\u7528\u91cf\uff1a").append(dosage);
                }
                if (remaining <= 5) r.append("\n\n\u4f59\u91cf\u9884\u8b66\uff1a\u8bf7\u63d0\u524d\u8865\u836f\u3002");
                return r.toString();
            }
        }
        return drugName + " \u5c1a\u672a\u5728\u7528\u836f\u6863\u6848\u4e2d\u767b\u8bb0\u3002";
    }

    public synchronized String recordProfileFact(String userId, String key, String value) {
        Map<String, Object> data = record(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) data.computeIfAbsent("profile", ignored -> new LinkedHashMap<>());
        profile.put(key, value);
        profile.put("\u6700\u540e\u66f4\u65b0", now());
        save(userId, data);
        return "\u5df2\u66f4\u65b0\u5065\u5eb7\u6863\u6848\uff1a" + key + " = " + value;
    }

    public String screenSymptoms(String symptoms) {
        String text = symptoms == null ? "" : symptoms.trim();
        if (text.isEmpty()) return "请描述症状、持续时间和严重程度，我会先做健康筛查提示。";
        boolean urgent = text.contains("胸痛") || text.contains("呼吸困难") || text.contains("意识不清")
                || text.contains("大出血") || text.contains("言语不清");
        if (urgent) return "检测到可能需要紧急处理的症状（" + text + "）。请立即拨打 120 或前往最近急诊，本提示不能替代医生诊断。";
        return "初筛记录：" + text + "。建议关注体温、血压等变化；若症状持续、加重或影响日常活动，请尽快线上问诊或到医院就诊。";
    }

    public String interpretReport(String reportText) {
        String text = reportText == null ? "" : reportText.trim();
        if (text.isEmpty()) return "请发送体检报告文字或图片识别结果后再进行解读。";
        boolean abnormal = text.contains("异常") || text.contains("偏高") || text.contains("偏低")
                || text.contains("阳性") || text.contains("↑") || text.contains("↓");
        List<String> focus = new ArrayList<>();
        addIfContains(focus, text, "血糖", "血糖与糖代谢");
        addIfContains(focus, text, "糖化血红蛋白", "长期血糖水平");
        addIfContains(focus, text, "血压", "血压");
        addIfContains(focus, text, "胆固醇", "血脂");
        addIfContains(focus, text, "甘油三酯", "血脂");
        addIfContains(focus, text, "肝功能", "肝功能");
        addIfContains(focus, text, "肾功能", "肾功能");
        addIfContains(focus, text, "尿酸", "尿酸");
        StringBuilder out = new StringBuilder("体检报告初步解读\n\n")
                .append("结果：").append(abnormal ? "发现异常关键词，需要结合参考区间复核" : "当前文字中未识别到明确异常关键词");
        if (!focus.isEmpty()) out.append("\n关注项目：").append(String.join("、", focus.stream().distinct().toList()));
        out.append("\n\n下一步\n")
                .append("1. 对照报告中的参考范围和复查建议\n")
                .append("2. 携带完整报告咨询医生，由医生结合症状和既往病史判断\n")
                .append("3. 若伴明显不适或指标被标注危急值，请及时就医\n\n")
                .append("本解读仅用于健康信息整理，不能替代医生诊断，也不能作为自行调整用药的依据。");
        return out.toString();
    }

    public synchronized String saveHealthReport(String userId, String reportText) {
        String cleaned = reportText == null ? "" : reportText.trim();
        if (cleaned.isEmpty()) return "未检测到报告内容，请发送体检报告文字或图片识别结果。";
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> reports = list(data, "healthReports");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("text", cleaned);
        report.put("hasAbnormalKeyword", hasAbnormalKeyword(cleaned));
        report.put("savedAt", now());
        reports.add(report);
        save(userId, data);
        return "体检报告已归档\n\n"
                + "报告摘要：" + summary(cleaned, 60) + "\n"
                + "归档时间：" + report.get("savedAt") + "\n\n"
                + interpretReport(cleaned);
    }

    public String interpretLatestReport(String userId) {
        List<Map<String, Object>> reports = list(record(userId), "healthReports");
        if (reports.isEmpty()) return "个人健康档案中暂无体检报告，请先发送报告文字或图片。";
        return interpretReport(String.valueOf(reports.get(reports.size() - 1).get("text")));
    }

    public synchronized String clearAllHealthData(String userId) {
        Map<String, Object> data = record(userId);
        data.clear();
        save(userId, data);
        records.remove(userId);
        return "\u5065\u5eb7\u6863\u6848\u5df2\u6e05\u7a7a\n\n"
                + "\u6240\u6709\u7528\u836f\u8bb0\u5f55\u3001\u5065\u5eb7\u6307\u6807\u3001\u4f53\u68c0\u62a5\u544a\u3001\u5065\u5eb7\u65b9\u6848\u548c\u4e2a\u4eba\u4fe1\u606f\u5df2\u5168\u90e8\u5220\u9664\u3002\n"
                + "\u53ef\u91cd\u65b0\u5f00\u59cb\u767b\u8bb0\u7528\u836f\u548c\u8bb0\u5f55\u5065\u5eb7\u6570\u636e\u3002";
    }

    public synchronized String savePlan(String userId, String conditions, String goal, String content) {
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> plans = list(data, "healthPlans");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("conditions", conditions);
        plan.put("goal", goal);
        plan.put("content", content);
        plan.put("createdAt", now());
        plans.add(plan);
        save(userId, data);
        return content + "\n\n\u5df2\u4fdd\u5b58\u5230\u4e2a\u4eba\u5065\u5eb7\u6863\u6848\u3002";
    }

    public String plan(String conditions, String goal) {
        String c = conditions == null || conditions.isBlank() ? "无特殊病症" : conditions;
        String g = goal == null || goal.isBlank() ? "保持健康" : goal;
        String normalized = (c + " " + g).toLowerCase(Locale.ROOT);
        String diet = "餐盘的一半安排非淀粉类蔬菜，四分之一优质蛋白，四分之一全谷物或薯类";
        String recipe = "早餐：无糖酸奶/牛奶配燕麦和鸡蛋；午餐：杂粮饭、清蒸鱼和两份蔬菜；晚餐：豆制品、菌菇蔬菜和少量主食";
        String exercise = "每周累计 150 分钟中等强度有氧活动，并安排 2 次力量练习，从能轻松完成的强度开始";
        if (containsAny(normalized, "高血压", "控压", "血压")) {
            diet = "采用少盐饮食，减少腌制品、加工肉和高钠调味料，优先新鲜蔬果、全谷物与低脂蛋白";
            recipe = "早餐：燕麦、鸡蛋和无糖奶；午餐：杂粮饭、清蒸鱼、绿叶菜；晚餐：豆腐菌菇汤和蔬菜，烹调少盐";
        } else if (containsAny(normalized, "糖尿病", "控糖", "血糖")) {
            diet = "规律三餐，控制精制糖和含糖饮料，主食优先全谷物并与蔬菜、蛋白质搭配";
            recipe = "早餐：全麦面包、鸡蛋和无糖奶；午餐：糙米、鸡胸肉和蔬菜；晚餐：杂豆、鱼虾和蔬菜";
        } else if (containsAny(normalized, "减重", "减肥", "体重")) {
            diet = "保持适度能量缺口，优先高纤维蔬菜和优质蛋白，减少油炸食品、甜饮和夜宵";
            recipe = "早餐：鸡蛋、无糖酸奶和水果；午餐：半碗杂粮饭、瘦肉和两份蔬菜；晚餐：豆腐或鱼虾配蔬菜";
            exercise = "从每周 4 次快走或骑行开始，每次 30 分钟，逐步加入 2 次全身力量训练并记录体重趋势";
        }
        return "个性化健康方案\n\n"
                + "健康特征：" + c + "\n目标：" + g + "\n\n"
                + "饮食规划\n" + diet + "。\n\n"
                + "一日食谱参考\n" + recipe + "。\n\n"
                + "运动安排\n" + exercise + "。\n\n"
                + "执行建议：每周回顾体重、血压或血糖趋势，根据身体反应调整活动量。慢病、孕期或运动中出现不适时，请先咨询医生或专业人员。";
    }

    public String dailyBriefingWithWeather(String userId, String city) {
        String briefing = dailyBriefing(userId, city);
        if (city == null || city.isBlank()) return briefing;
        try {
            var weather = weatherService.getWeather(city);
            return briefing + "\n\n天气与季节提醒\n" + SmartWeatherService.getHealthAdvice(weather);
        } catch (Exception e) {
            log.warn("daily health weather lookup failed city={}", city, e);
            return briefing;
        }
    }

    public String dailyBriefing(String userId, String city) {
        String date = LocalDate.now().toString();
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> meds = list(data, "medications");
        List<Map<String, Object>> vitals = list(data, "vitals");
        StringBuilder out = new StringBuilder("今日健康简报  |  " + date + "\n\n今日用药\n");
        if (meds.isEmpty()) out.append("暂无已登记用药\n");
        for (Map<String, Object> med : meds) {
            int remaining = intValue(med.get("remaining"));
            out.append("• ").append(med.get("name")).append("  ")
                    .append(safe(String.valueOf(med.get("schedule")), "未设置时段"))
                    .append("  余量 ").append(remaining);
            if (remaining <= 5) out.append("  [需补药]");
            out.append("\n");
        }
        if (!vitals.isEmpty()) {
            Map<String, Object> latest = vitals.get(vitals.size() - 1);
            out.append("\n最近指标\n• ").append(latest.get("type")).append("：")
                    .append(latest.get("value")).append("  ").append(latest.get("measuredAt")).append("\n");
        }
        out.append("\n今日陪伴\n• ").append(chronicCareTip(data))
                .append("\n• ").append(seasonalTip())
                .append("\n• 城市：").append(city == null || city.isBlank() ? "未设置" : city);
        return out.toString();
    }

    public synchronized String recordMeal(String userId, String foods, int estimatedCalories, String mealType, String note) {
        Map<String, Object> data = record(userId);
        List<Map<String, Object>> logs = list(data, "mealLogs");
        Map<String, Object> meal = new LinkedHashMap<>();
        meal.put("date", LocalDate.now().toString());
        meal.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        meal.put("foods", foods);
        meal.put("estimatedCalories", estimatedCalories);
        meal.put("mealType", mealType == null || mealType.isEmpty() ? "正餐" : mealType);
        if (note != null && !note.isEmpty()) meal.put("note", note);
        logs.add(meal);
        save(userId, data);
        return "已记录" + mealType + "：" + foods + "（约" + estimatedCalories + "千卡）";
    }

    public int todayCalories(String userId) {
        String today = LocalDate.now().toString();
        return list(record(userId), "mealLogs").stream()
                .filter(m -> today.equals(String.valueOf(m.get("date"))))
                .mapToInt(m -> intValue(m.get("estimatedCalories")))
                .sum();
    }

    public List<Map<String, Object>> todayMeals(String userId) {
        String today = LocalDate.now().toString();
        return list(record(userId), "mealLogs").stream()
                .filter(m -> today.equals(String.valueOf(m.get("date"))))
                .toList();
    }

    public String dailyDietarySummary(String userId) {
        List<Map<String, Object>> meals = todayMeals(userId);
        int totalCal = todayCalories(userId);
        if (meals.isEmpty()) return "今日暂无饮食记录。";
        StringBuilder sb = new StringBuilder("今日饮食记录\n");
        for (Map<String, Object> m : meals) {
            sb.append("• ").append(m.get("time")).append(" ")
              .append(m.get("mealType")).append("：")
              .append(m.get("foods")).append("（约").append(m.get("estimatedCalories")).append("千卡）\n");
        }
        sb.append("今日合计：约").append(totalCal).append("千卡");
        return sb.toString();
    }

    private String chronicCareTip(Map<String, Object> data) {
        String profileText = String.valueOf(data.getOrDefault("profile", ""));
        if (profileText.contains("高血压")) return "按医嘱用药并在相近时段测量血压，记录连续趋势比单次结果更有意义。";
        if (profileText.contains("糖尿病") || profileText.contains("血糖")) return "规律进餐和监测血糖，记录饮食、活动与血糖变化，复诊时一并提供。";
        return "规律饮水、保证睡眠，并安排适量活动；身体不适时及时咨询专业人员。";
    }

    private String seasonalTip() {
        Month month = LocalDate.now().getMonth();
        if (month == Month.JUNE || month == Month.JULY || month == Month.AUGUST) return "高温季节注意补水、防晒，避免在正午进行高强度户外运动。";
        if (month == Month.DECEMBER || month == Month.JANUARY || month == Month.FEBRUARY) return "低温季节注意保暖，慢病人群外出前关注气温变化。";
        return "换季时注意温差，保持室内通风和规律作息。";
    }

    private boolean hasAbnormalKeyword(String text) {
        return containsAny(text, "异常", "偏高", "偏低", "阳性", "↑", "↓", "危急值");
    }

    private void addIfContains(List<String> target, String text, String keyword, String label) {
        if (text.contains(keyword)) target.add(label);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.ofPattern("M月d日 HH时");

    private static String now() {
        return LocalDateTime.now().format(DAILY_FMT);
    }

    public String findLastLoggedMedicationName(String userId) {
        List<Map<String, Object>> logs = list(record(userId), "medicationLogs");
        if (logs.isEmpty()) return null;
        Map<String, Object> last = logs.get(logs.size() - 1);
        String name = String.valueOf(last.get("name"));
        return name == null || name.isEmpty() || name.contains("未指定") ? null : name;
    }

    private int intValue(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? fallback : value;
    }

    private String summary(Object value, int limit) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> data, String key) {
        Object value = data.computeIfAbsent(key, k -> new ArrayList<Map<String, Object>>());
        return (List<Map<String, Object>>) value;
    }

    private Map<String, Object> load(String userId) {
        try {
            Files.createDirectories(dataDir);
            File file = dataDir.resolve(fileName(userId)).toFile();
            if (file.exists()) return mapper.readValue(file, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) { log.warn("加载健康档案失败 userId={}", userId, e); }
        return new LinkedHashMap<>();
    }

    private void save(String userId, Map<String, Object> data) {
        try {
            Files.createDirectories(dataDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(dataDir.resolve(fileName(userId)).toFile(), data);
        } catch (Exception e) { log.warn("保存健康档案失败 userId={}", userId, e); }
    }

    private String fileName(String userId) {
        return userId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";
    }
}
