package com.elderlyhealth.agent.bot;

import com.elderlyhealth.agent.entity.TaskErrorType;


public class ErrorRouter {
    public static TaskErrorType classify(Throwable e){
        String msg=e.getMessage() !=null ?e.getMessage().toLowerCase():"";
        if(msg.contains("timeout")|| msg.contains("time out")||msg.contains("read time out")){
            return TaskErrorType.RETRYABLE;
        }
        if (msg.contains("rate limit")||msg.contains("quota")||msg.contains("too many requests")){
            return TaskErrorType.DEGRADE;
        }
        if(msg.contains("auth")||msg.contains("login")||msg.contains("unauthorized")||msg.contains("扫码")){
            return TaskErrorType.AUTH_REQUIRED;
        }
        return TaskErrorType.FATAL;
    }

    public static int  maxRetires(TaskErrorType type){
        return switch (type){
            case RETRYABLE -> 3;
            case AUTH_REQUIRED -> 0;
            case DEGRADE -> 0;
            case FATAL -> 0;
        };
    }
    public static String userMessage(TaskErrorType type){
        return switch (type){
            case RETRYABLE -> "暂时遇到网络波动，正在重试……";
            case AUTH_REQUIRED -> "需要你进行身份验证才能继续，请提供相关信息";
            case DEGRADE -> "当前系统繁忙，已切换到备用方案。";
            case FATAL -> "操作遇到了无法恢复的错误，任务已经终止。";
        };
    }

}

