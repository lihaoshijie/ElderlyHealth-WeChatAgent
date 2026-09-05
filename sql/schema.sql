-- ============================================================
-- ElderlyHealth-WeChatAgent (银龄智护) 数据库建表脚本
-- 数据库: elderly_health_db（UTF-8）
-- 说明: 本文件为唯一权威 schema，src/main/resources/schema.sql 与其保持一致。
-- 用法: mysql -uroot -p elderly_health_db < sql/schema.sql
-- ============================================================
-- 用户信息表
CREATE TABLE IF NOT EXISTS admin_user_info (
    from_user_id VARCHAR(64) PRIMARY KEY,
    first_msg_time DATETIME,
    last_msg_time DATETIME,
    msg_count INT DEFAULT 0
);

-- 对话消息表
CREATE TABLE IF NOT EXISTS admin_user_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (from_user_id, created_at)
);

-- ===== 统一消息模型改造 =====
-- 新增 msg_type / direction / msg_id（关联资源附表）
ALTER TABLE admin_user_messages
    ADD COLUMN IF NOT EXISTS msg_type VARCHAR(16) NOT NULL DEFAULT 'text' COMMENT '消息类型: text/image/file/voice' AFTER role,
    ADD COLUMN IF NOT EXISTS direction VARCHAR(16) NOT NULL DEFAULT 'user' COMMENT '消息方向: user/bot' AFTER msg_type,
    ADD COLUMN IF NOT EXISTS msg_id BIGINT DEFAULT NULL COMMENT '关联资源附表主键' AFTER direction,
    ADD INDEX IF NOT EXISTS idx_resource (msg_id);

-- 存量数据回填
UPDATE admin_user_messages SET direction = CASE WHEN role = 'user' THEN 'user' ELSE 'bot' END WHERE direction = 'user' AND role = 'assistant';
UPDATE admin_user_messages SET msg_type = 'text' WHERE msg_type = 'text';

-- 图片记录表（增加 msg_id 外键）
CREATE TABLE IF NOT EXISTS admin_user_images (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(64) NOT NULL,
    disk_path VARCHAR(512),
    recognized_desc TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (from_user_id)
);

-- 文件记录表
CREATE TABLE IF NOT EXISTS admin_user_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(256),
    disk_path VARCHAR(512),
    content_text LONGTEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (from_user_id)
);

-- 管理员表
CREATE TABLE IF NOT EXISTS admin_user (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) UNIQUE NOT NULL,
    password VARCHAR(128) NOT NULL
);

-- 种子数据: 默认管理员 admin/admin123
INSERT INTO admin_user (username, password) VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5E');

-- 对话历史表（ChatService）
CREATE TABLE IF NOT EXISTS chat_history (
    user_id VARCHAR(64) PRIMARY KEY,
    content TEXT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 任务表（Task）
CREATE TABLE IF NOT EXISTS tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    context TEXT,
    error_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 订单表
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) UNIQUE NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    plan_summary VARCHAR(512),
    total_amount DECIMAL(10,2) DEFAULT 0,
    status VARCHAR(16) DEFAULT 'PENDING',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_order_no (order_no)
);

-- 订单扩展字段（幂等键/游玩人/日期）
ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotent_key VARCHAR(64) DEFAULT NULL AFTER order_no;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS travel_date VARCHAR(32) DEFAULT NULL AFTER plan_summary;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS travelers INT DEFAULT 1 AFTER travel_date;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS contact_name VARCHAR(32) DEFAULT NULL AFTER travelers;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(20) DEFAULT NULL AFTER contact_name;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS remark TEXT DEFAULT NULL AFTER contact_phone;
CREATE INDEX IF NOT EXISTS idx_idempotent ON orders(idempotent_key);

-- 电子票券表
CREATE TABLE IF NOT EXISTS tickets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no VARCHAR(32) UNIQUE NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    attraction_name VARCHAR(128) NOT NULL,
    visit_date VARCHAR(16) NOT NULL,
    quantity INT DEFAULT 1,
    unit_price DECIMAL(10,2) DEFAULT 0,
    total_price DECIMAL(10,2) DEFAULT 0,
    status VARCHAR(16) DEFAULT 'VALID',
    qr_data TEXT,
    issued_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    used_at DATETIME DEFAULT NULL,
    INDEX idx_order (order_no),
    INDEX idx_user (user_id)
);

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) DEFAULT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) DEFAULT NULL,
    resource_id VARCHAR(64) DEFAULT NULL,
    detail TEXT,
    ip_address VARCHAR(45) DEFAULT NULL,
    result VARCHAR(16) DEFAULT 'SUCCESS',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_action (action),
    INDEX idx_time (created_at)
);

-- Agent 调用记录表
CREATE TABLE IF NOT EXISTS agent_traces (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64),
    round_num INT DEFAULT 0,
    tool_name VARCHAR(64),
    tool_args TEXT,
    tool_result TEXT,
    status VARCHAR(16),
    retry_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_task (task_id)
);

-- ===== 统一消息模型：资源表增加 msg_id 外键 =====
ALTER TABLE admin_user_images
    ADD COLUMN IF NOT EXISTS msg_id BIGINT DEFAULT NULL AFTER id,
    ADD INDEX IF NOT EXISTS idx_msg_id (msg_id);

ALTER TABLE admin_user_files
    ADD COLUMN IF NOT EXISTS msg_id BIGINT DEFAULT NULL AFTER id,
    ADD INDEX IF NOT EXISTS idx_msg_id (msg_id);

-- 语音记录表
CREATE TABLE IF NOT EXISTS admin_user_voices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    msg_id BIGINT NOT NULL,
    from_user_id VARCHAR(64) NOT NULL,
    disk_path VARCHAR(512) NOT NULL,
    duration INT DEFAULT 0,
    transcript TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (from_user_id),
    INDEX idx_msg_id (msg_id)
);

-- 体征记录表（血压 / 血糖 / 心率 / 体重等）
CREATE TABLE IF NOT EXISTS health_vital_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    value VARCHAR(128),
    measured_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_time (measured_at)
);

-- ===== 补充表（doc_embeddings / scheduled_tasks） =====

CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    cron VARCHAR(64),
    trigger_at DATETIME,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    fail_reason VARCHAR(512),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS doc_embeddings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_name VARCHAR(256) NOT NULL,
    chunk_index INT NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding JSON NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    uploader VARCHAR(64) DEFAULT NULL,
    uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FULLTEXT INDEX ft_chunk_text (chunk_text) WITH PARSER ngram,
    INDEX idx_uploader (uploader)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 兼容补充：admin_user_info.nickname（旧库升级幂等）
ALTER TABLE admin_user_info ADD COLUMN IF NOT EXISTS nickname VARCHAR(128) DEFAULT NULL;

-- ============================================================
-- M1: 家庭联动 / 体征预警
-- ============================================================

-- 家庭绑定（长辈-守护人）
-- status: INVITED(已生成邀请码待确认) / ACTIVE(已绑定)
-- elder_user_id = 长辈的微信用户ID; guardian_user_id = 子女/守护人的微信用户ID
CREATE TABLE IF NOT EXISTS family_bind (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    elder_user_id VARCHAR(64) NOT NULL,
    guardian_user_id VARCHAR(64) DEFAULT NULL,
    invite_code VARCHAR(16) DEFAULT NULL,
    relation VARCHAR(32) DEFAULT '子女',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_elder_guardian (elder_user_id, guardian_user_id),
    INDEX idx_guardian (guardian_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 体征预警记录（红/黄级别，供追溯与子女通知去重）
CREATE TABLE IF NOT EXISTS health_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    vital_type VARCHAR(32) NOT NULL,
    vital_value VARCHAR(64),
    level VARCHAR(8) NOT NULL,
    advice TEXT,
    notified_guardian TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;