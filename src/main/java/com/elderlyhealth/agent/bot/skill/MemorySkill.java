package com.elderlyhealth.agent.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MemorySkill implements Skill {

    @Override
    public String getName() { return "记忆"; }

    @Override
    public String getRule() {
        return "记忆规则：用户透露个人信息（名字、生日、城市、喜好、职业等）时，立即调 remember_fact 保存，不要等用户明确要求。"
                + "保存后简短确认即可，不需要每次回复都复述记忆内容。"
                + "后续对话中如果用户问相关内容（如生日快到了、推荐本地餐厅），根据记忆中的信息个性化回答。";
    }

    @Override
    public List<String> getTools() {
        return List.of("remember_fact");
    }
}
