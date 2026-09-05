package com.Myself.demo.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class HealthVitalRecord {
    private Long id;
    private String userId;
    private String type;
    private String value;
    private LocalDateTime measuredAt;
    private LocalDateTime createdAt;
}
