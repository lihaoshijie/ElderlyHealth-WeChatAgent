package com.Myself.demo.tool;

import com.Myself.demo.bot.ToolExecutionContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class QrCodeTool {

    @Tool(name = "qr_code", value = "生成二维码")
    public String qrCode(
            @P("二维码内容") String text) {
        ToolExecutionContext.recordQrCode(text);
        return "二维码已生成并发送";
    }

    @Tool(name = "decode_qr_code", value = "解码二维码图片")
    public String decodeQr() {
        ToolExecutionContext.recordDecodeQr();
        return "正在识别二维码...";
    }
}
