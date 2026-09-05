package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.bot.ToolExecutionContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class ImageTool {

    @Tool(name = "generate_image", value = "从零生成全新图片")
    public String generateImage(
            @P("图片生成提示词，如：一只可爱的猫") String prompt) {
        ToolExecutionContext.recordImageGen(prompt);
        return "正在生成图片中...";
    }

    @Tool(name = "transform_image", value = "变换编辑已有图片")
    public String transformImage(
            @P("图片变换提示词") String prompt) {
        ToolExecutionContext.recordImageTransform(prompt);
        return "正在处理图片中...";
    }

    @Tool(name = "re_examine_image", value = "重新识别已发送图片")
    public String reExamineImage(
            @P("重识别问题") String question) {
        ToolExecutionContext.recordReExamine(question);
        return "正在重新识别图片...";
    }
}
