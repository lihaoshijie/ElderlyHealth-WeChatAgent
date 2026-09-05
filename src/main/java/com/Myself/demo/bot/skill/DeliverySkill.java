package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DeliverySkill implements Skill {

    @Override
    public String getName() { return "快递"; }

    @Override
    public String getRule() {
        return "快递查询规则：用户未提供快递公司时先询问。中通、申通查询必须提供收件人手机尾号后4位，缺了就反问用户。"
                + "查询结果中包含物流状态时用emoji标注（已签收✅ 运输中🚚 已揽收📦 异常⚠️）。"
                + "如果返回'暂无物流信息'，提醒用户稍后再查或核对单号。";
    }

    @Override
    public List<String> getTools() {
        return List.of("query_delivery");
    }
}
