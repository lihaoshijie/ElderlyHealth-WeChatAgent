package com.elderlyhealth.agent.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class HealthFoodSkill implements Skill {

    @Override
    public String getName() { return "健康饮食"; }

    @Override
    public String getRule() {
        return "查询食物时同时调 food_nutrition(营养成分)+health_tip(健康建议)。体脂查询时顺带调 health_tip 给健康建议。";
    }

    @Override
    public List<String> getTools() {
        return List.of("body_fat_rate", "food_nutrition", "caipu", "health_tip");
    }
}
