package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WeatherSkill implements Skill {

    @Override
    public String getName() { return "天气"; }

    @Override
    public String getRule() {
        return "天气回复规则：结果用emoji美化。查天气时自动同时查空气质量air_quality。不要重复描述'我来查一下'等计划性语言。";
    }

    @Override
    public List<String> getTools() {
        return List.of("weather", "air_quality");
    }
}
