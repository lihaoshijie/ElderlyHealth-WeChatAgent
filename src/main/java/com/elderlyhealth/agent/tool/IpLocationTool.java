package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.AmapService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IpLocationTool {

    private final AmapService amapService;

    public IpLocationTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Tool(name = "get_user_location", value = "获取用户地理位置坐标")
    public String getLocation(
            @P("用户所在城市名") String cityHint) {

        if (cityHint == null || cityHint.trim().isEmpty()) {
            return "【定位失败】未提供城市名。请先询问用户在哪个城市，获取城市名后再调用本工具。";
        }

        String city = cityHint.trim();
        String coord = amapService.getCoord(city, city);

        if (coord != null && coord.contains(",")) {
            String[] parts = coord.split(",");
            return String.format("城市：%s\n经度：%s\n纬度：%s\n（高德地图精确坐标）", city, parts[0], parts[1]);
        }

        return String.format("城市：%s\n（未查询到该城市的精确坐标，但仍可按城市名搜索附近门店）", city);
    }
}
