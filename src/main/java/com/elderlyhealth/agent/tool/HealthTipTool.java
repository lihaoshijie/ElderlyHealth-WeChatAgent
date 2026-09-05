package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.HealthTipService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 接入TianAPI
 * 健康小妙招
 */
@Component
public class HealthTipTool {

    private final HealthTipService healthTipService;

    public HealthTipTool(HealthTipService healthTipService) {
        this.healthTipService = healthTipService;
    }

    @Tool(name = "health_tip", value = "查询健康小妙招")
    public String healthTip(
            @P("症状关键词，如：失眠、颈椎疼") String word) {
        try {
            String result = healthTipService.getTip(word);
            if (result == null) {
                return "关于【" + word + "】暂无相关小妙招，请根据自己的知识回答";
            }
            return result;
        } catch (Exception e) {
            return "健康小妙招查询失败，请稍后再试";
        }
    }
}
