package com.Myself.demo.tool;

import com.Myself.demo.service.VoicePreferenceService;
import com.Myself.demo.service.VoiceType;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class VoiceTool {

    private final VoicePreferenceService voicePreferenceService;

    public VoiceTool(VoicePreferenceService voicePreferenceService) {
        this.voicePreferenceService = voicePreferenceService;
    }

    @Tool(name = "switch_voice", value = "切换语音播报音色")
    public String switchVoice(
            @P("音色名称") String voiceName,
            @P("用户ID") String userId) {
        try {
            VoiceType vt = VoiceType.fromName(voiceName);
            voicePreferenceService.setVoiceCode(userId, vt.getCode());
            return "已切换音色为 " + vt.getDescription();
        } catch (Exception e) {
            return "音色切换失败，请重试";
        }
    }

    @Tool(name = "enable_voice", value = "启用语音播报")
    public String enableVoice(@P("用户ID") String userId) {
        voicePreferenceService.enableVoice(userId);
        return "语音播报已开启";
    }

    @Tool(name = "disable_voice", value = "关闭语音播报")
    public String disableVoice(@P("用户ID") String userId) {
        voicePreferenceService.disableVoice(userId);
        return "语音播报已关闭";
    }

    @Tool(name = "list_voice_types", value = "列出所有可用音色")
    public String listVoiceTypes() {
        StringBuilder sb = new StringBuilder("可用音色：\n");
        for (VoiceType vt : VoiceType.values()) {
            sb.append(vt.getDescription()).append("\n");
        }
        return sb.toString();
    }
}
