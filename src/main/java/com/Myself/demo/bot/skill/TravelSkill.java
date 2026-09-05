package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class TravelSkill implements Skill {

    @Override
    public String getName() { return "旅游"; }

    @Override
    public String getRule() {
        return "旅游分三阶段推进："
                + "\n①用户提旅游需求→调 generate_travel_guide,返回内容原样完整发给用户,禁止自行生成任何购票链接。"
                + "\n②用户选定方案并明确订票(如\"方案一,订火车票\")→此时 get_ticket_buy_link 工具才可用,用它生成订票链接。"
                + "\n③用户确认订票后→调 generate_full_itinerary 生成整体行程(出发/当地/返程),原样发送。";
    }

    @Override
    public List<String> getTools() {
        return List.of("generate_travel_guide", "search_poi", "generate_travel_plan",
                "generate_full_itinerary", "get_ticket_buy_link", "fliggy_travel_search");
    }
}
