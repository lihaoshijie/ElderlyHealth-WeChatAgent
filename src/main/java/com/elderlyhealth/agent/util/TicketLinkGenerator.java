package com.elderlyhealth.agent.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class TicketLinkGenerator {

    public enum TicketType { TRAINS, FLIGHT }

    /**
     * 仅生成兜底网页链接。scheme 由中转页本地构造，避免微信拦截协议串。
     */
    public static String getWebUrl(TicketType type, String startName, String endName, String travelDate) {
        String from = encode(startName);
        String to = encode(endName);
        String date = encode(travelDate);
        return switch (type) {
            case TRAINS -> "https://kyfw.12306.cn/otn/leftTicket/init";
            case FLIGHT -> "https://m.fliggy.com/travel/search?depCity=" + from + "&arrCity=" + to + "&depDate=" + date;
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }
}
