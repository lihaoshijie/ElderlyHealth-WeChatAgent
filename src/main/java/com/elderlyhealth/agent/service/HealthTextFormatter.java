package com.elderlyhealth.agent.service;

import java.util.List;
import java.util.Map;

public final class HealthTextFormatter {
    private HealthTextFormatter() { }

    public static String vitalSummary(String weight, String pressure, String sugar) {
        StringBuilder out = new StringBuilder("\u5065\u5eb7\u6307\u6807\u8bb0\u5f55\n\n");
        if (weight != null) out.append("\u4f53\u91cd\uff1a").append(weight).append("\n");
        if (pressure != null) out.append("\u8840\u538b\uff1a").append(pressure).append("\n");
        if (sugar != null) out.append("\u8840\u7cd6\uff1a").append(sugar).append("\n");
        out.append("\n\u8840\u538b\u89e3\u8bfb\n")
                .append("- \u8bf7\u4f18\u5148\u8865\u5145\u5b8c\u6574\u8840\u538b\uff08\u6536\u7f29\u538b/\u8212\u5f20\u538b\uff09\uff0c\u4f8b\u5982 120/80 mmHg\n")
                .append("- \u6d4b\u91cf\u524d\u9759\u5750 5 \u5206\u949f\uff0c\u907f\u514d\u8fd0\u52a8\u3001\u5496\u5561\u540e\u7acb\u5373\u6d4b\u91cf\n")
                .append("- \u82e5\u6301\u7eed\u5f02\u5e38\u6216\u4f34\u968f\u4e0d\u9002\uff0c\u8bf7\u53ca\u65f6\u54a8\u8be2\u533b\u751f\n\n")
                .append("\u4e3a\u4e86\u5b8c\u5584\u6863\u6848\uff0c\u8fd8\u53ef\u4ee5\u8865\u5145\uff1a\n")
                .append("1. \u5b8c\u6574\u8840\u538b\u548c\u5fc3\u7387\n2. \u8eab\u9ad8\uff08\u7528\u4e8e BMI \u8ba1\u7b97\uff09\n3. \u65e2\u5f80\u75c5\u53f2\u4e0e\u6b63\u5728\u4f7f\u7528\u7684\u836f\u7269\n\n")
                .append("\u26a0\ufe0f \u4ee5\u4e0a\u4ec5\u4f5c\u5065\u5eb7\u79d1\u666e\u53c2\u8003\uff0c\u4e0d\u80fd\u66ff\u4ee3\u533b\u751f\u8bca\u65ad\u3002");
        return out.toString();
    }

    public static String overview(List<Map<String, Object>> meds, List<Map<String, Object>> vitals) {
        StringBuilder out = new StringBuilder("\u4e2a\u4eba\u5065\u5eb7\u6863\u6848\n\n")
                .append("\u8bf4\u660e\uff1a\u6570\u636e\u6309\u7528\u6237\u9694\u79bb\u4fdd\u5b58\uff0c\u4ec5\u4f5c\u4e2a\u4eba\u5065\u5eb7\u8bb0\u5f55\u3002\n\n")
                .append("\u7528\u836f\u6863\u6848\uff08").append(meds.size()).append(" \u9879\uff09\n");
        if (meds.isEmpty()) out.append("- \u6682\u65e0\u767b\u8bb0\u7528\u836f\n");
        else for (Map<String, Object> med : meds) {
            out.append("- ").append(med.get("name")).append("\uff1a").append(med.get("dosage"))
                    .append(" \uff0c\u65f6\u6bb5 ").append(med.get("schedule"))
                    .append(" \uff0c\u4f59\u91cf ").append(med.get("remaining")).append("\n");
        }
        out.append("\n\u5065\u5eb7\u6307\u6807\uff08").append(vitals.size()).append(" \u6761\uff09\n");
        if (vitals.isEmpty()) out.append("- \u6682\u65e0\u8bb0\u5f55\n");
        else for (Map<String, Object> vital : vitals) {
            out.append("- ").append(vital.get("type")).append("\uff1a").append(vital.get("value"))
                    .append(" \uff08").append(vital.get("measuredAt")).append("\uff09\n");
        }
        out.append("\n\u4f60\u53ef\u4ee5\u76f4\u63a5\u53d1\u9001\uff1a\u201c\u4f53\u91cd 70kg\u201d\u3001\u201c\u8840\u538b 120/80\u201d\u6216\u201c\u767b\u8bb0\u7528\u836f\u201d\u6765\u66f4\u65b0\u6863\u6848\u3002");
        return out.toString();
    }

    public static String symptomTriage(String symptoms, boolean urgent) {
        StringBuilder out = new StringBuilder("\u75c7\u72b6\u521d\u7b5b\n\n")
                .append("\u5df2\u8bb0\u5f55\uff1a").append(symptoms).append("\n\n");
        if (urgent) {
            out.append("\u26a0\ufe0f \u9ad8\u98ce\u9669\u63d0\u793a\n")
                    .append("\u5982\u75c7\u72b6\u6b63\u5728\u53d1\u751f\u6216\u52a0\u91cd\uff0c\u8bf7\u7acb\u5373\u62e8\u6253 120 \u6216\u524d\u5f80\u6025\u8bca\u3002\n\n");
        } else {
            out.append("\u5efa\u8bae\n")
                    .append("- \u8bf7\u8865\u5145\u75c7\u72b6\u6301\u7eed\u65f6\u95f4\u3001\u75bc\u75db\u90e8\u4f4d\u548c\u7a0b\u5ea6\n")
                    .append("- \u7559\u610f\u4f53\u6e29\u3001\u5455\u5410\u3001\u8179\u6cfb\u3001\u51fa\u8840\u7b49\u4f34\u968f\u75c7\u72b6\n")
                    .append("- \u6301\u7eed\u3001\u52a0\u91cd\u6216\u5f71\u54cd\u65e5\u5e38\u6d3b\u52a8\u65f6\uff0c\u8bf7\u5c3d\u5feb\u7ebf\u4e0a\u95ee\u8bca\u6216\u5c31\u533b\u3002\n\n");
        }
        String guidance = symptomGuidance(symptoms, urgent);
        if (!guidance.isEmpty()) {
            out.append("\u9488\u5bf9\u5f53\u524d\u75c7\u72b6\n").append(guidance).append("\n\n");
        }
        return out.append("\u26a0\ufe0f \u4ee5\u4e0a\u4ec5\u4f5c\u5065\u5eb7\u79d1\u666e\u53c2\u8003\uff0c\u4e0d\u80fd\u66ff\u4ee3\u533b\u751f\u8bca\u65ad\u3002").toString();
    }

    private static String symptomGuidance(String symptoms, boolean urgent) {
        if (urgent) return "- 请优先处理危险信号，不要独自驾车；症状缓解也建议记录发生时间和变化。";
        if (containsAny(symptoms, "\u5934\u75db", "\u5934\u75bc", "\u504f\u5934\u75db")) {
            return "- 先在安静、光线较暗的环境休息，适量饮水，暂时减少屏幕和酒精\n"
                    + "- 可测量体温和血压，并记录开始时间、疼痛位置、程度、睡眠和进食情况\n"
                    + "- 若突然出现从未有过的剧烈头痛，或伴视物异常、肢体无力、言语不清、颈部僵硬、反复呕吐，请立即就医";
        }
        if (containsAny(symptoms, "\u8179\u75db", "\u809a\u5b50\u75db", "\u80c3\u75db")) {
            return "- 先记录疼痛位置、开始时间、程度以及进食后的变化，避免酒精和刺激性食物\n"
                    + "- 观察是否伴随发热、持续呕吐、腹泻、黑便或便血\n"
                    + "- 疼痛持续加重、固定在右下腹或伴明显虚弱时，请尽快就医";
        }
        if (containsAny(symptoms, "\u53d1\u70e7", "\u53d1\u70ed", "\u4f53\u6e29\u9ad8")) {
            return "- 测量并记录体温，适量补水和休息，保持室内通风\n"
                    + "- 同时记录咳嗽、咽痛、皮疹、腹泻等伴随表现\n"
                    + "- 高热不退、精神状态明显变差或出现呼吸困难时，请及时就医";
        }
        if (containsAny(symptoms, "\u54b3\u55fd", "\u5e72\u54b3", "\u5589\u5499\u75db")) {
            return "- 适量饮水，避免烟雾、粉尘和冷空气刺激，记录症状持续时间\n"
                    + "- 若出现喘憋、胸痛、口唇发紫或咳血，请立即就医";
        }
        if (containsAny(symptoms, "\u6076\u5fc3", "\u5455\u5410", "\u8179\u6cfb", "\u62c9\u809a\u5b50")) {
            return "- 少量多次补充水分，暂时选择清淡易消化食物，记录次数和是否伴发热\n"
                    + "- 无法进食饮水、明显尿量减少、便血或持续加重时，请及时就医";
        }
        return "- 请补充症状开始时间、诱因、严重程度和伴随表现，便于后续咨询\n"
                + "- 若症状持续、加重或影响日常活动，请尽快咨询医生";
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) if (value != null && value.contains(keyword)) return true;
        return false;
    }
}
