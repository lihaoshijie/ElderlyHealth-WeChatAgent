package com.Myself.demo.service;

import com.Myself.demo.entity.AuditLog;
import com.Myself.demo.mapper.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void log(String userId, String action, String resourceType, String resourceId, String detail, String ip, boolean success) {
        AuditLog al = new AuditLog();
        al.setUserId(userId);
        al.setAction(action);
        al.setResourceType(resourceType);
        al.setResourceId(resourceId);
        al.setDetail(detail);
        al.setIpAddress(ip);
        al.setResult(success ? "SUCCESS" : "FAIL");
        auditLogMapper.insert(al);
        log.info("审计日志: {} | {} | {} | {}", action, resourceId, detail, al.getResult());
    }

    public void logSuccess(String userId, String action, String resourceType, String resourceId, String detail) {
        log(userId, action, resourceType, resourceId, detail, null, true);
    }

    public void logFail(String userId, String action, String resourceType, String resourceId, String detail) {
        log(userId, action, resourceType, resourceId, detail, null, false);
    }
}
