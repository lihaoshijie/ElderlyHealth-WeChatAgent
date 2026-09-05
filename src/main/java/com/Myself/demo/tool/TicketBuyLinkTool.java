package com.Myself.demo.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TicketBuyLinkTool {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Tool(name = "get_ticket_buy_link", value = "生成火车票或机票的购票链接")
    public String getBuyLink(
            @P("票类型，trains或flight") String type,
            @P("出发地") String startName,
            @P("目的地") String endName,
            @P("出发日期，yyyy-MM-dd") String travelDate,
            @P("车次号，航班可填空") String trainNo) {

        String platform = "flight".equals(type) ? "携程" : "12306";
        String emoji = "flight".equals(type) ? "✈️" : "🚄";
        String no = trainNo != null && !trainNo.isEmpty() ? trainNo : "";
        String paramKey = "flight".equals(type) ? "flightNo" : "trainNo";

        String transitUrl = baseUrl + "/ticket-transit.html"
                + "?type=" + e(type) + "&" + paramKey + "=" + e(no)
                + "&from=" + e(startName) + "&to=" + e(endName) + "&date=" + e(travelDate);

        return emoji + " 出行购票信息\n"
                + ("flight".equals(type) ? "航班" : "车次") + "：" + (no.isEmpty() ? startName + "→" + endName : no) + "\n"
                + "出发：" + startName + "\n"
                + "到达：" + endName + "\n"
                + "日期：" + travelDate + "\n\n"
                + "请复制下方链接，粘贴到手机浏览器打开（微信内直接打开会受限）\n"
                + transitUrl + "\n\n"
                + "📌 操作说明：\n"
                + "1、长按链接复制；\n"
                + "2、打开手机自带浏览器粘贴访问；\n"
                + "3、页面自动尝试唤起官方App；若无对应App，则打开网页版购票；\n"
                + "⚠️安全提醒：仅跳转官方购票渠道，切勿向任何人提供账号、短信验证码。";
    }

    private String e(String s) {
        return java.net.URLEncoder.encode(s != null ? s : "", StandardCharsets.UTF_8);
    }
}
