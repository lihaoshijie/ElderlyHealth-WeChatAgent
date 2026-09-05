package com.Myself.demo.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户每日提醒配置服务。
 * 存储每个用户创建的提醒，支持按用户查询、按时间匹配、标记已触发。
 * 数据存储在内存中，重启后丢失。
 */
@Service
public class ReminderConfigService {

    private final ConcurrentHashMap<String, List<Reminder>> storage = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    /** 为用户创建一个提醒 */
    public Reminder add(String userId, String toolName, String params, int hour, int minute) {
        Reminder r = new Reminder(
                idCounter.getAndIncrement(),
                userId,
                toolName,
                params,
                hour,
                minute,
                true);
        storage.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>())).add(r);
        return r;
    }

    /** 获取用户的所有提醒 */
    public List<Reminder> list(String userId) {
        return storage.getOrDefault(userId, Collections.emptyList());
    }

    /** 按 ID 取消提醒，返回被取消的提醒，不存在则返回 null */
    public Reminder cancel(String userId, int id) {
        List<Reminder> items = storage.get(userId);
        if (items == null) return null;
        for (Reminder r : items) {
            if (r.id == id) {
                items.remove(r);
                return r;
            }
        }
        return null;
    }

    /** 获取所有已开启且已到执行时间的提醒 */
    public List<Reminder> getDueReminders(int currentHour, int currentMinute) {
        List<Reminder> result = new ArrayList<>();
        String today = LocalDate.now().toString();
        for (List<Reminder> items : storage.values()) {
            for (Reminder r : items) {
                if (!r.enabled) continue;
                if (today.equals(r.lastTriggeredDate)) continue;
                if (r.hour == currentHour && r.minute == currentMinute) {
                    result.add(r);
                }
            }
        }
        return result;
    }

    /** 标记提醒已触发 */
    public void markTriggered(Reminder r) {
        r.lastTriggeredDate = LocalDate.now().toString();
    }

    /**
     * 提醒数据模型。
     * toolName=null 时，params 为自定义文字，直接发送；否则调用对应工具。
     */
    public static class Reminder {
        public final int id;
        public final String userId;
        public final String toolName;    // null 表示自定义文字
        public final String params;      // JSON 参数或自定义文字内容
        public final int hour;
        public final int minute;
        public boolean enabled;
        volatile String lastTriggeredDate;  // yyyy-MM-dd，防止同一天重复触发

        Reminder(int id, String userId, String toolName, String params,
                 int hour, int minute, boolean enabled) {
            this.id = id;
            this.userId = userId;
            this.toolName = toolName;
            this.params = params;
            this.hour = hour;
            this.minute = minute;
            this.enabled = enabled;
        }
    }
}