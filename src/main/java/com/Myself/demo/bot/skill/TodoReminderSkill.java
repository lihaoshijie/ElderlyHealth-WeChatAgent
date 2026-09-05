package com.Myself.demo.bot.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class TodoReminderSkill implements Skill {

    @Override
    public String getName() { return "待办提醒"; }

    @Override
    public String getRule() {
        return "添加/完成/删除待办后自动调用 list_todos 展示当前列表。创建/取消提醒后自动调用 list_reminders 确认已设置。";
    }

    @Override
    public List<String> getTools() {
        return List.of("add_todo", "list_todos", "delete_todo", "mark_todo_done",
                "create_reminder", "list_reminders", "cancel_reminder");
    }
}
