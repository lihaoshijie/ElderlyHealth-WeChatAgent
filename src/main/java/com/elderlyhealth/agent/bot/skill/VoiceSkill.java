package com.elderlyhealth.agent.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class VoiceSkill implements Skill {

    @Override
    public String getName() { return "语音"; }

    @Override
    public String getRule() {
        return "语音仅本次生效，用完自动关。用户未明确要求语音时不要主动调用 enable_voice。";
    }

    @Override
    public List<String> getTools() {
        return List.of("enable_voice", "disable_voice", "switch_voice", "list_voice_types");
    }
}
