package com.Myself.demo.mapper;

import com.Myself.demo.entity.Task;
import com.Myself.demo.entity.TaskStatus;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TaskMapper {
    Task findById(@Param("taskId") String taskId);
    List<Task> findByUserIdAndStatus(@Param("userId") String userId, @Param("status") TaskStatus status);
    void insert(Task task);
    void updateContext(@Param("taskId") String taskId, @Param("context") String context, @Param("status") TaskStatus status);
    void updateStatus(@Param("taskId") String taskId, @Param("status") TaskStatus status);
    void incrementError(@Param("taskId") String taskId);
    void delete(@Param("taskId") String taskId);
}
