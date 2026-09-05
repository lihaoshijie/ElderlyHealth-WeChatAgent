package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.TodoService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TodoTool {

    private final TodoService todoService;

    public TodoTool(TodoService todoService) {
        this.todoService = todoService;
    }

    @Tool(name = "add_todo", value = "添加待办事项")
    public String addTodo(
            @P("待办内容") String content,
            @P("提醒时间") String remindAt,
            @P("提前分钟数") int remindBefore,
            @P("用户ID") String userId) {
        if (remindAt != null && !remindAt.isEmpty() && remindBefore > 0) {
            LocalDateTime time = LocalDateTime.parse(remindAt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            remindAt = time.minusMinutes(remindBefore).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return todoService.add(userId, content, remindAt);
    }

    @Tool(name = "list_todos", value = "查看待办列表")
    public String listTodos(@P("用户ID") String userId) {
        return todoService.list(userId);
    }

    @Tool(name = "delete_todo", value = "删除待办事项")
    public String deleteTodo(
            @P("待办编号") int id,
            @P("用户ID") String userId) {
        return todoService.delete(userId, id);
    }

    @Tool(name = "mark_todo_done", value = "标记待办已完成")
    public String markDone(
            @P("待办编号") int id,
            @P("用户ID") String userId) {
        return todoService.markDone(userId, id);
    }
}
