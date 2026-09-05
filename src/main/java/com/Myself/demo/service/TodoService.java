package com.Myself.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class TodoService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public String add(String userId, String content, String remindAt) {
        List<Item> items = load(userId);
        int id = items.isEmpty() ? 1 : items.get(items.size() - 1).id + 1;
        items.add(new Item(id, content, LocalDateTime.now().format(formatter), false, remindAt, false));
        save(userId, items);
        log.info("待办已添加: userId={}, id={}", userId, id);
        return "已添加待办 #" + id + ": " + content;
    }

    public String list(String userId) {
        List<Item> items = load(userId);
        List<Item> undone = items.stream().filter(t -> !t.done).toList();
        if (undone.isEmpty()) {
            return "暂无待办事项";
        }
        StringBuilder sb = new StringBuilder("📋 待办事项（共" + undone.size() + "项）\n");
        for (int i = 0; i < undone.size(); i++) {
            Item t = undone.get(i);
            sb.append("\n").append(i + 1).append(". ").append(t.content);
            sb.append("\n   创建: ").append(t.createdAt);
            if (t.remindAt != null && !t.remindAt.isEmpty()) {
                sb.append("  ⏰ ").append(t.remindAt);
            }
        }
        return sb.toString();
    }

    public String delete(String userId, int id) {
        List<Item> items = load(userId);
        boolean removed = items.removeIf(t -> t.id == id);
        if (removed) {
            save(userId, items);
            log.info("待办已删除: userId={}, id={}", userId, id);
            return "已删除待办 #" + id;
        }
        return "未找到编号为 " + id + " 的待办";
    }

    public String markDone(String userId, int id) {
        List<Item> items = load(userId);
        for (Item t : items) {
            if (t.id == id) {
                items.remove(t);
                items.add(new Item(t.id, t.content, t.createdAt, true, t.remindAt, t.reminded));
                save(userId, items);
                log.info("待办已完成: userId={}, id={}", userId, id);
                return "✅ 已完成: " + t.content;
            }
        }
        return "未找到编号为 " + id + " 的待办";
    }

    public record DueItem(String userId, int id, String content) {}

    public List<DueItem> getDueItems() {
        List<DueItem> result = new ArrayList<>();
        File dir = new File(".");
        File[] files = dir.listFiles((d, name) -> name.startsWith("todos-") && name.endsWith(".json"));
        if (files == null) return result;
        String now = LocalDateTime.now().format(formatter);
        for (File f : files) {
            String userId = f.getName().replace("todos-", "").replace(".json", "");
            List<Item> items = load(userId);
            for (Item item : items) {
                if (!item.done && !item.reminded && item.remindAt != null && !item.remindAt.isEmpty() && item.remindAt.compareTo(now) <= 0) {
                    result.add(new DueItem(userId, item.id, item.content));
                }
            }
        }
        return result;
    }

    public void markReminded(String userId, int id) {
        List<Item> items = load(userId);
        for (int i = 0; i < items.size(); i++) {
            Item t = items.get(i);
            if (t.id == id) {
                items.set(i, new Item(t.id, t.content, t.createdAt, t.done, t.remindAt, true));
                save(userId, items);
                return;
            }
        }
    }

    private List<Item> load(String userId) {
        File file = new File("todos-" + userId + ".json");
        if (!file.exists()) return new CopyOnWriteArrayList<>();
        try {
            List<Item> items = objectMapper.readValue(file, new TypeReference<List<Item>>() {});
            return new CopyOnWriteArrayList<>(items);
        } catch (Exception e) {
            log.warn("加载待办文件失败: {}", e.getMessage());
            return new CopyOnWriteArrayList<>();
        }
    }

    private void save(String userId, List<Item> items) {
        try {
            objectMapper.writeValue(new File("todos-" + userId + ".json"), new ArrayList<>(items));
        } catch (Exception e) {
            log.warn("保存待办文件失败: {}", e.getMessage());
        }
    }

    private record Item(int id, String content, String createdAt, boolean done, String remindAt, boolean reminded) {}
}
