package com.elderlyhealth.agent.bot.skill;

import java.util.List;

public interface Skill {
    String getName();
    String getRule();
    List<String> getTools();
}
