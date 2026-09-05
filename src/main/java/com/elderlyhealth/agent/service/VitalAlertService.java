package com.elderlyhealth.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 银龄智护 · 体征三级预警（M1）
 *
 * 分级口径（供参考的可调阈值，非医疗诊断）：
 *  - 血压：收缩≥180 或 舒张≥110 → RED；140-179 / 90-109 → YELLOW；其余 GREEN
 *  - 血糖：≥16.7 → RED（危险高值）；7.0-16.6 空腹 → YELLOW（需复查）；3.9-6.9 → GREEN
 *  - 心率：≥130 或 ≤40 → RED；100-129 / 41-54 → YELLOW；55-99 → GREEN
 *  - 体温：≥39.0 → RED；37.3-38.9 → YELLOW；其余 GREEN
 *  - 血氧：≤90 → RED；91-94 → YELLOW；≥95 → GREEN
 * 红色预警自动落库 health_alert，并尝试通知已绑定守护人。
 */
@Slf4j
@Service
public class VitalAlertService {

    private final JdbcTemplate jdbcTemplate;
    private final FamilyGuardService familyGuardService;

    public VitalAlertService(JdbcTemplate jdbcTemplate, FamilyGuardService familyGuardService) {
        this.jdbcTemplate = jdbcTemplate;
        this.familyGuardService = familyGuardService;
    }

    /** 根据体征类型与用户输入文本判定预警级别。 */
    public String evaluate(String type, String rawValue) {
        String t = type == null ? "" : type.trim();
        String v = rawValue == null ? "" : rawValue.trim();
        if (t.isEmpty() || v.isEmpty()) return "GREEN";
        String norm = t.toLowerCase();
        if (norm.contains("血压")) return evaluateBloodPressure(v);
        if (norm.contains("血糖")) return evaluateGlucose(v);
        if (norm.contains("心率") || norm.contains("脉搏")) return evaluateHeartRate(v);
        if (norm.contains("体温")) return evaluateTemperature(v);
        if (norm.contains("血氧")) return evaluateOxygen(v);
        return "GREEN";
    }

    /** 把一次体征记录接入预警链路：返回用户可见的预警文案（无预警返回 null）。 */
    public String checkAndNotify(String userId, String type, String value, String measuredAt) {
        try {
            String level = evaluate(type, value);
            if ("GREEN".equals(level)) return null;

            boolean red = "RED".equals(level);
            String advice = buildAdvice(type, value, red);
            // 落库 health_alert
            try {
                jdbcTemplate.update(
                    "INSERT INTO health_alert (user_id, vital_type, vital_value, level, advice, notified_guardian) VALUES (?,?,?,?,?,?)",
                    userId, type == null ? "" : type, value, level, advice, red ? 1 : 0);
            } catch (Exception e) {
                log.warn("health_alert 落库失败(不影响主流程): {}", e.getMessage());
            }
            if (red) {
                boolean notified = familyGuardService.notifyGuardiansOfVitalAlert(userId, type, value, "RED", advice);
                return "\n\n⚠️ 【红色预警】" + type + " " + value
                        + "\n" + advice
                        + (notified ? "\n已通知您的家人，请保持电话畅通。" : "\n建议尽快联系家人或就医。");
            }
            return "\n\n【黄色提示】" + type + " " + value + "，" + advice + "\n请继续观察，如有不适及时就医。";
        } catch (Exception e) {
            log.warn("体征预警处理失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildAdvice(String type, String value, boolean red) {
        String head = red ? "该数值已达危险区间，请立即复核测量，如有胸闷/头晕/呼吸困难等不适请马上拨打 120 或就医。" :
                "该数值偏高/偏低，建议休息后复核，注意按时用药与作息。";
        return head + "（本提示为健康管理参考，不构成医疗诊断。）";
    }

    // ---------- 解析 ----------

    private String evaluateBloodPressure(String v) {
        Matcher m = Pattern.compile("(\\d{2,3})\\s*[/／]\\s*(\\d{2,3})").matcher(v);
        if (!m.find()) {
            // 可能是"高压150低压95"这类表述
            Matcher sys = Pattern.compile("(?:高压|收缩压)\\s*[=:：]?\\s*(\\d{2,3})").matcher(v);
            Matcher dia = Pattern.compile("(?:低压|舒张压)\\s*[=:：]?\\s*(\\d{2,3})").matcher(v);
            if (sys.find() && dia.find()) {
                return levelByRange(Integer.parseInt(sys.group(1)), Integer.parseInt(dia.group(1)));
            }
            return "GREEN";
        }
        int s = Integer.parseInt(m.group(1));
        int d = Integer.parseInt(m.group(2));
        return levelByRange(s, d);
    }

    private String levelByRange(int sys, int dia) {
        if (sys >= 180 || dia >= 110) return "RED";
        if (sys >= 140 || dia >= 90) return "YELLOW";
        return "GREEN";
    }

    private String evaluateGlucose(String v) {
        Matcher m = Pattern.compile("(\\d{1,2}(\\.\\d+)?)").matcher(v);
        if (!m.find()) return "GREEN";
        double g = Double.parseDouble(m.group(1));
        if (g >= 16.7) return "RED";
        if (g >= 7.0) return "YELLOW";
        if (g < 2.8) return "RED"; // 低血糖危险
        return "GREEN";
    }

    private String evaluateHeartRate(String v) {
        Matcher m = Pattern.compile("(\\d{2,3})").matcher(v);
        if (!m.find()) return "GREEN";
        int hr = Integer.parseInt(m.group(1));
        if (hr >= 130 || hr <= 40) return "RED";
        if (hr >= 100 || hr <= 54) return "YELLOW";
        return "GREEN";
    }

    private String evaluateTemperature(String v) {
        Matcher m = Pattern.compile("(3[0-9](?:\\.\\d)?|4[0-2](?:\\.\\d)?)").matcher(v);
        if (!m.find()) return "GREEN";
        double t = Double.parseDouble(m.group(1));
        if (t >= 39.0) return "RED";
        if (t >= 37.3) return "YELLOW";
        return "GREEN";
    }

    private String evaluateOxygen(String v) {
        Matcher m = Pattern.compile("(\\d{2,3})").matcher(v);
        if (!m.find()) return "GREEN";
        int o = Integer.parseInt(m.group(1));
        if (o <= 90) return "RED";
        if (o <= 94) return "YELLOW";
        return "GREEN";
    }
}
