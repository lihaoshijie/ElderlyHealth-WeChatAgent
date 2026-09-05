package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImageSkill implements Skill {

    @Override
    public String getName() { return "图片"; }

    @Override
    public String getRule() {
        return "图片回复规则：图片生成后不要再在文字回复中说'正在生成'或重复图片描述。重识别时直接回答用户问题，不加无关描述。";
    }

    @Override
    public List<String> getTools() {
        return List.of("generate_image", "transform_image", "re_examine_image");
    }
}
