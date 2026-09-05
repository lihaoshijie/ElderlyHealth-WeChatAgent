package com.elderlyhealth.agent.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserFile {
    private Long id;
    private Long msgId;
    private String fromUserId;
    private String fileName;
    private String diskPath;
    private String contentText;
    private LocalDateTime createdAt;
}
