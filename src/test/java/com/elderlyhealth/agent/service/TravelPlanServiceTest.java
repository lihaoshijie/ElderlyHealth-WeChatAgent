package com.elderlyhealth.agent.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelPlanServiceTest {

    private TravelPlanService svc = new TravelPlanService(null, null);

    private Map<String, Object> poi(String name, String cat, String level, boolean free, double lat, double lng) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("category", cat);
        m.put("level", level);
        m.put("free", free);
        m.put("price", free ? "" : "");
        m.put("lat", String.valueOf(lat));
        m.put("lng", String.valueOf(lng));
        m.put("description", "");
        return m;
    }

    @Test
    void runPlanLoopCoversAllDays() {
        List<Map<String, Object>> attractions = new ArrayList<>();
        attractions.add(poi("A", "公园乐园", "5", false, 31.1, 118.1));
        attractions.add(poi("B", "公园乐园", "5", false, 31.1, 118.2));
        attractions.add(poi("C", "博物馆", "4", false, 31.2, 118.2));
        attractions.add(poi("D", "博物馆", "4", false, 31.3, 118.3));
        attractions.add(poi("E", "公园乐园", "", false, 31.4, 118.4));
        attractions.add(poi("F", "公园乐园", "", false, 31.5, 118.5));

        Map<String, Double> prices = new HashMap<>();
        prices.put("A", 70.0);
        prices.put("B", 40.0);
        prices.put("C", 30.0);
        prices.put("D", 20.0);
        prices.put("E", 10.0);
        prices.put("F", 5.0);

        List<List<Map<String, Object>>> plan = svc.runPlanLoop(new ArrayList<>(attractions), 3, prices, attractions);

        assertEquals(3, plan.size());
        for (List<Map<String, Object>> day : plan) {
            assertTrue(day.size() <= 2, "每天最多2个景点, 实际=" + day.size());
        }
    }

    @Test
    void runPlanLoopFillsShortageWithFreeAttractions() {
        List<Map<String, Object>> attractions = new ArrayList<>();
        attractions.add(poi("A", "公园乐园", "5", false, 31.1, 118.1));
        attractions.add(poi("B", "博物馆", "4", false, 31.2, 118.2));
        attractions.add(poi("C", "公园乐园", "", true, 31.4, 118.4));
        attractions.add(poi("D", "公园乐园", "", true, 31.5, 118.5));
        attractions.add(poi("E", "公园乐园", "", true, 31.6, 118.6));
        attractions.add(poi("F", "公园乐园", "", true, 31.7, 118.7));

        Map<String, Double> prices = new HashMap<>();
        prices.put("A", 70.0);
        prices.put("B", 30.0);

        // 只有2个收费景点，3天行程不足，应补入免费景点填满天数
        List<Map<String, Object>> pool = new ArrayList<>();
        pool.add(attractions.get(0));
        pool.add(attractions.get(1));

        List<List<Map<String, Object>>> plan = svc.runPlanLoop(pool, 3, prices, attractions);

        assertEquals(3, plan.size());
        for (List<Map<String, Object>> day : plan) {
            assertTrue(day.size() <= 2, "每天最多2个景点, 实际=" + day.size());
        }
    }
}
