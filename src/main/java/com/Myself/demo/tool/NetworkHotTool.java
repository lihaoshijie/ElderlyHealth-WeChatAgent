package com.Myself.demo.tool;

import com.Myself.demo.service.NetworkHotService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NetworkHotTool {

    private final NetworkHotService networkHotService;

    public NetworkHotTool(NetworkHotService networkHotService) {
        this.networkHotService = networkHotService;
    }

    @Tool(name = "network_hot", value = "查询全网实时热搜榜单，仅返回当前热门话题标题列表。注意：本工具只返回热搜榜单，不能获取任何具体新闻/话题的详细内容；用户询问特定话题、事件、人物的具体信息时，必须改用 baidu_search 或 google_search，不要调用本工具。")
    public String getNetworkHot() {
        log.info("查询全网热搜");
        return networkHotService.getHotList();
    }
}
