package com.elderlyhealth.agent.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserVoice {
    private Long id;
    private Long msgId;
    private String fromUserId;
    private String diskPath;
    private Integer duration;
    private String transcript;
    private LocalDateTime createdAt;
}
