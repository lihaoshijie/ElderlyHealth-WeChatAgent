package com.elderlyhealth.agent.mapper;

import com.elderlyhealth.agent.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface AuditLogMapper {
    void insert(AuditLog log);
    List<AuditLog> findByUserId(String userId);
    List<AuditLog> findByAction(String action);
}
