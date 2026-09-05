package com.Myself.demo.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserImage {
    private Long id;
    private Long msgId;
    private String fromUserId;
    private String diskPath;
    private String recognizedDesc;
    private LocalDateTime createdAt;
}
