package com.Myself.demo.tool;

import com.Myself.demo.service.ReminderConfigService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ReminderTool {

    private final ReminderConfigService configService;

    public ReminderTool(ReminderConfigService configService) {
        this.configService = configService;
    }

    @Tool(name = "create_reminder", value = "创建每日定时提醒")
    public String createReminder(
            @P("提醒时间(HH:mm)") String time,
            @P("工具名称") String toolName,
            @P("参数或提醒文字") String params,
            @P("用户ID") String userId) {
        try {
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return "时间格式错误，请使用 HH:mm 格式，如 08:00";
            }

            String actualToolName = (toolName == null || toolName.isBlank()) ? null : toolName.trim();
            ReminderConfigService.Reminder r = configService.add(userId, actualToolName, params, hour, minute);

            String label = actualToolName != null ? "工具「" + actualToolName + "」" : "自定义文字";
            log.info("用户 {} 创建提醒: id={}, time={}:{}, tool={}", userId, r.id, hour, minute, actualToolName);
            return String.format("已创建提醒 #%d：每天 %02d:%02d %s", r.id, hour, minute, label);
        } catch (Exception e) {
            log.error("创建提醒失败", e);
            return "创建提醒失败: " + e.getMessage();
        }
    }

    @Tool(name = "list_reminders", value = "列出所有每日提醒")
    public String listReminders(@P("用户ID") String userId) {
        List<ReminderConfigService.Reminder> items = configService.list(userId);
        if (items.isEmpty()) {
            return "你还没有设置任何每日提醒。试试说「每天早上8点提醒我天气」";
        }
        StringBuilder sb = new StringBuilder("你的每日提醒（共").append(items.size()).append("项）\n");
        for (ReminderConfigService.Reminder r : items) {
            String type = r.toolName != null ? "工具「" + r.toolName + "」" : "文字「" + r.params + "」";
            sb.append("\n").append(r.id).append(". ")
              .append(String.format("%02d:%02d", r.hour, r.minute))
              .append(" ").append(type)
              .append(r.enabled ? "" : " [已暂停]");
        }
        return sb.toString();
    }

    @Tool(name = "cancel_reminder", value = "取消每日提醒")
    public String cancelReminder(
            @P("提醒编号") int id,
            @P("用户ID") String userId) {
        ReminderConfigService.Reminder removed = configService.cancel(userId, id);
        if (removed == null) {
            return "未找到编号为 " + id + " 的提醒，请使用 list_reminders 查看你的提醒列表";
        }
        String label = removed.toolName != null ? removed.toolName : "自定义文字";
        log.info("用户 {} 取消提醒: id={}, tool={}", userId, id, removed.toolName);
        return String.format("已取消提醒 #%d：每天 %02d:%02d %s", id, removed.hour, removed.minute, label);
    }
}