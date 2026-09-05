package com.elderlyhealth.agent.mapper;

import com.elderlyhealth.agent.entity.HealthVitalRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface HealthVitalRecordMapper {
    void insert(HealthVitalRecord record);
    List<HealthVitalRecord> findRecentByUserId(@Param("userId") String userId, @Param("limit") int limit);
}
