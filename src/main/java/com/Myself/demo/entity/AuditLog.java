package com.Myself.demo.entity;

import lombok.Data;

@Data
public class AuditLog {
    private Long id;
    private String userId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String detail;
    private String ipAddress;
    private String result;
    private String createdAt;
}
