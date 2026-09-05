package com.Myself.demo.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PharmacyResponseFormatter {
    public String format(String location, List<Map<String, Object>> pois) {
        String titleLocation = location == null || location.isBlank() ? "" : location;
        StringBuilder out = new StringBuilder("\u9644\u8fd1\u836f\u5e97");
        if (!titleLocation.isBlank()) out.append("\uff08").append(titleLocation).append("\uff09");
        out.append("\n\n");
        int total = Math.min(5, pois.size());
        int primary = Math.min(3, total);
        for (int i = 0; i < primary; i++) appendPrimary(out, i + 1, pois.get(i));
        if (total > primary) {
            out.append("\n\u5468\u8fb9\u7a0d\u8fdc\u5907\u9009\n\n");
            for (int i = primary; i < total; i++) {
                Map<String, Object> p = pois.get(i);
                out.append("\u2022 \u3010").append(p.get("name")).append("\u3011\uff1a")
                        .append(p.get("address"));
                appendDistance(out, p);
                out.append("\n");
            }
        }
        out.append("\n\ud83d\udca1 \u63d0\u793a\uff1a\u5230\u5e97\u524d\u5efa\u8bae\u786e\u8ba4\u8425\u4e1a\u65f6\u95f4\uff1b\u8d2d\u4e70\u5904\u65b9\u836f\u8bf7\u643a\u5e26\u75c5\u5386\u6216\u5904\u65b9\u3002");
        return out.toString();
    }

    private void appendPrimary(StringBuilder out, int index, Map<String, Object> p) {
        out.append(index).append(". ").append(p.get("name")).append("\n");
        out.append("\ud83d\udccd ").append(p.get("address"));
        appendDistance(out, p);
        out.append("\n");
        Object type = p.get("type");
        if (type != null && !type.toString().isBlank()) out.append("\u7c7b\u578b\uff1a").append(type).append("\n");
        out.append("\n");
    }

    private void appendDistance(StringBuilder out, Map<String, Object> p) {
        Object distance = p.get("distance");
        if (distance != null && !distance.toString().isBlank()) out.append("\uff0c\u7ea6 ").append(distance).append(" \u7c73");
    }
}
