package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.TarotService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 塔罗牌占卜工具类
 * 功能： 1.单张指引 ⭐适合快速问答  比如"今天适合表白吗""这件事该不该做"，抽一张牌给你直接指引。
 *       2.三张牌阵 ⭐默认  适合了解事情发展脉络 按"过去-现在-未来"或"起因-经过-结果"来解读，比较全面。
 *       3.十字牌阵 ⭐适合深入分析复杂问题  会从核心状态、阻碍、根源、发展、结果五个维度来看，更详细。
 */
@Component
public class TarotTool {

    private final TarotService tarotService;

    public TarotTool(TarotService tarotService) {
        this.tarotService = tarotService;
    }

    @Tool(name = "tarot", value = "塔罗牌占卜解读")
    public String tarot(
            @P("用户问题") String question,
            @P("牌阵类型") String spread) {
        try {
            String result = tarotService.draw(spread);
            return result;
        } catch (Exception e) {
            return "塔罗占卜失败，请重试";
        }
    }
}
