package com.elderlyhealth.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Task {
    private String taskId;
    private String userId;
    private String taskType;
    private TaskStatus status;
    private String context;   // JSON string
    private Integer errorCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
