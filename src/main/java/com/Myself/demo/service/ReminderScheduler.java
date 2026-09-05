package com.Myself.demo.service;

import com.Myself.demo.bot.ToolRegistry;
import com.Myself.demo.bot.WeChatBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ReminderScheduler {

    private final ReminderConfigService configService;
    private final ToolRegistry toolRegistry;
    private final WeChatBotService weChatBotService;

    public ReminderScheduler(ReminderConfigService configService,
                             ToolRegistry toolRegistry,
                             WeChatBotService weChatBotService) {
        this.configService = configService;
        this.toolRegistry = toolRegistry;
        this.weChatBotService = weChatBotService;
    }

    @Scheduled(fixedRate = 60000)
    public void checkAndPush() {
        LocalDateTime now = LocalDateTime.now();
        List<ReminderConfigService.Reminder> dueList = configService.getDueReminders(now.getHour(), now.getMinute());

        for (ReminderConfigService.Reminder r : dueList) {
            try {
                String content;
                if (r.toolName == null) {
                    // 自定义文字提醒，直接使用 params 作为内容
                    content = r.params;
                } else {
                    // 调用工具获取内容
                    String result = toolRegistry.execute(r.toolName, r.params, r.userId);
                    content = (result != null) ? result : "工具执行失败: " + r.toolName;
                }

                boolean sent = weChatBotService.sendReminder(r.userId,
                        "【" + formatLabel(r) + "】\n" + content);
                if (sent) {
                    configService.markTriggered(r);
                    log.info("提醒已推送: userId={}, tool={}, time={}:{}", r.userId, r.toolName, r.hour, r.minute);
                }
            } catch (Exception e) {
                log.error("推送提醒失败: userId={}, id={}", r.userId, r.id, e);
            }
        }
    }

    private static String formatLabel(ReminderConfigService.Reminder r) {
        return String.format("每天 %02d:%02d", r.hour, r.minute);
    }
}