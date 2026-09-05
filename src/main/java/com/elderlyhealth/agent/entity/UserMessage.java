package com.elderlyhealth.agent.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserMessage {
    private Long id;
    private String fromUserId;
    private String role;          // user / assistant（保留兼容）
    private String msgType;       // text / image / file / voice
    private String direction;     // user / bot
    private Long msgId;           // 关联资源附表主键，text 时为 null
    private String content;
    private LocalDateTime createdAt;

    // ===== LEFT JOIN 填充字段（非DB列）=====
    private String resourceUrl;   // 资源访问地址
    private String resourceExtra; // JSON: {"desc":"识图文本","fileName":"xxx","duration":5}
}
