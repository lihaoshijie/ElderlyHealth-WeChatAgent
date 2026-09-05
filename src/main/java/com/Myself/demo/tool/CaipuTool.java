package com.Myself.demo.tool;

import com.Myself.demo.service.CaipuService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class CaipuTool {

    private final CaipuService caipuService;

    public CaipuTool(CaipuService caipuService) {
        this.caipuService = caipuService;
    }

    @Tool(name = "caipu", value = "查询菜谱做法和原料")
    public String search(
            @P("食材或菜名，如：鱼香肉丝、黄瓜") String word) {
        try {
            String result = caipuService.search(word);
            if (result == null) {
                return "菜谱查询失败，请稍后再试";
            }
            return result;
        } catch (Exception e) {
            return "菜谱查询失败，请稍后再试";
        }
    }
}
