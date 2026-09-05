package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class BirthdaySkill implements Skill {

    @Override
    public String getName() { return "生日命理"; }

    @Override
    public String getRule() {
        return "生日命理规则：并行调用 eight_characters(排盘)+annual_fortune(流年)+element_preference(五行)+calendar_date(农历)+baidu_search(历史上的今天)。玄学工具(塔罗)不要自动调，回复时顺带问用户是否需要。";
    }

    @Override
    public List<String> getTools() {
        return List.of("eight_characters", "annual_fortune", "element_preference");
    }
}
