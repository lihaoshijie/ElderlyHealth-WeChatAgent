package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FileSkill implements Skill {

    @Override
    public String getName() { return "文件处理"; }

    @Override
    public String getRule() {
        return "文件处理规则：翻译/提取/搜索结果以TXT文件返回给用户。完成后询问用户是否需要导出文件总结。";
    }

    @Override
    public List<String> getTools() {
        return List.of("translate_file", "extract_from_file", "search_in_file",
                "export_file_summary", "save_as_file");
    }
}
