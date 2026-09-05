package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class SearchSkill implements Skill {

    @Override
    public String getName() { return "搜索"; }

    @Override
    public String getRule() {
        return "搜索返回结果中包含网址时，必须调用 qr_code 生成该网址的二维码，禁止只在文字中写「扫码可达」。搜到品牌/公司时，同时用 world_time 查当地时间。";
    }

    @Override
    public List<String> getTools() {
        return List.of("baidu_search", "google_search");
    }
}
