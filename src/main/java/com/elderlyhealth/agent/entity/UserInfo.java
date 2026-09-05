package com.elderlyhealth.agent.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserInfo {
    private String fromUserId;
    private String nickname;
    private LocalDateTime firstMsgTime;
    private LocalDateTime lastMsgTime;
    private int msgCount;
}
