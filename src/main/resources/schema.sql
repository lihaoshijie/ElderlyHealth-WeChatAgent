CREATE TABLE IF NOT EXISTS chat_history (
    user_id VARCHAR(64) PRIMARY KEY NOT NULL,
    content TEXT NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    context TEXT,
    error_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_traces (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    round_num INT NOT NULL DEFAULT 0,
    tool_name VARCHAR(128) NOT NULL,
    tool_args TEXT,
    tool_result TEXT,
    status VARCHAR(32) DEFAULT 'done',
    retry_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

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

CREATE TABLE IF NOT EXISTS admin_user_info (
    from_user_id VARCHAR(64) PRIMARY KEY,
    nickname VARCHAR(128),
    first_msg_time DATETIME,
    last_msg_time DATETIME,
    msg_count INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS admin_user_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (from_user_id, created_at)
);

ALTER TABLE admin_user_messages
    ADD COLUMN IF NOT EXISTS msg_type VARCHAR(16) NOT NULL DEFAULT 'text' COMMENT '消息类型: text/image/file/voice' AFTER role,
    ADD COLUMN IF NOT EXISTS direction VARCHAR(16) NOT NULL DEFAULT 'user' COMMENT '消息方向: user/bot' AFTER msg_type,
    ADD COLUMN IF NOT EXISTS msg_id BIGINT DEFAULT NULL COMMENT '关联资源附表主键' AFTER direction,
    ADD INDEX IF NOT EXISTS idx_resource (msg_id);

UPDATE admin_user_messages SET direction = CASE WHEN role = 'user' THEN 'user' ELSE 'bot' END WHERE direction = 'user' AND role = 'assistant';
UPDATE admin_user_messages SET msg_type = 'text' WHERE msg_type = 'text';

CREATE TABLE IF NOT EXISTS admin_user_images (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(64) NOT NULL,
    disk_path VARCHAR(512),
    recognized_desc TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (from_user_id)
);

CREATE TABLE IF NOT EXISTS admin_user_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(256),
    disk_path VARCHAR(512),
    content_text LONGTEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (from_user_id)
);

CREATE TABLE IF NOT EXISTS admin_user (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) UNIQUE NOT NULL,
    password VARCHAR(128) NOT NULL
);

INSERT IGNORE INTO admin_user (username, password) VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5E');

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
