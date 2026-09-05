package com.elderlyhealth.agent.service;

import com.elderlyhealth.agent.entity.Task;
import com.elderlyhealth.agent.entity.TaskStatus;
import com.elderlyhealth.agent.mapper.TaskMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TaskManager {

    @Autowired
    private TaskMapper taskMapper;

    public Task createTask(String userId,String taskType,String initialContext){
        Task task=new Task();
        task.setTaskId(UUID.randomUUID().toString().replace("-",""));
        task.setUserId(userId);
        task.setTaskType(taskType);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setContext(initialContext!=null? initialContext:"{}");
        task.setErrorCount(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }
    public Task getActiveTask(String userId){
        List<Task> tasks=taskMapper.findByUserIdAndStatus(userId,TaskStatus.IN_PROGRESS);
        if(tasks.isEmpty()){
            tasks=taskMapper.findByUserIdAndStatus(userId,TaskStatus.WAITING_AUTH);
        }
        return tasks.isEmpty() ?null:tasks.get(0);
    }
    public void updateContext(String taskId,String context,TaskStatus status){
        taskMapper.updateContext(taskId,context,status);
    }
    public void updateStatus(String taskId, TaskStatus status) {
        taskMapper.updateStatus(taskId,status);
    }
    public void incrementError(String taskId){
        taskMapper.incrementError(taskId);
    }
    public void completeTask(String taskId){
        taskMapper.updateStatus(taskId,TaskStatus.COMPLETED);
    }
    public void  failTask(String taskId){
        taskMapper.updateStatus(taskId,TaskStatus.FAILED);
    }

}
