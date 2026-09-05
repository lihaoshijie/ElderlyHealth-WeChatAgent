package com.elderlyhealth.agent.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class NewsHotSkill implements Skill {

    @Override
    public String getName() { return "热搜新闻"; }

    @Override
    public String getRule() {
        return "热搜返回标题列表后，如果用户追问某条详情，自动调 ai_news 获取AI摘要，不要自己编造内容。";
    }

    @Override
    public List<String> getTools() {
        return List.of("network_hot", "ai_news");
    }
}
