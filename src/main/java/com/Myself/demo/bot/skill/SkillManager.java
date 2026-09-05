package com.Myself.demo.bot.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SkillManager {

    private final List<Skill> allSkills;
    private final Map<String, List<Skill>> toolSkillMap = new HashMap<>();

    public SkillManager(List<Skill> allSkills) {
        this.allSkills = allSkills;
    }

    @PostConstruct
    public void init() {
        for (Skill skill : allSkills) {
            for (String toolName : skill.getTools()) {
                toolSkillMap.computeIfAbsent(toolName, k -> new ArrayList<>()).add(skill);
            }
        }
        long bound = toolSkillMap.values().stream().flatMap(List::stream).distinct().count();
        log.info("SkillManager 初始化完成，{} 个 Skill，绑定 {} 个工具位", allSkills.size(), bound);
    }

    public List<Skill> getSkillsFor(String toolName) {
        return toolSkillMap.getOrDefault(toolName, List.of());
    }
}
