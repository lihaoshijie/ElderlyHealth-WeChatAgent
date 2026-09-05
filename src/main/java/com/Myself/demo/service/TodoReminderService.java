package com.Myself.demo.service;

import com.Myself.demo.bot.WeChatBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TodoReminderService {

    private final TodoService todoService;
    private final WeChatBotService weChatBotService;

    public TodoReminderService(TodoService todoService, WeChatBotService weChatBotService) {
        this.todoService = todoService;
        this.weChatBotService = weChatBotService;
    }

    @Scheduled(fixedRate = 60000)
    public void checkReminders() {
        var dueItems = todoService.getDueItems();
        for (var item : dueItems) {
            boolean sent = weChatBotService.sendReminder(item.userId(), item.content());
            if (sent) {
                todoService.markReminded(item.userId(), item.id());
            }
        }
        if (!dueItems.isEmpty()) {
            log.info("定时提醒检查完成，已推送{}条", dueItems.size());
        }
    }
}
