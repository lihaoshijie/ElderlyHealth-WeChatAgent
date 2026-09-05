package com.Myself.demo.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

@Slf4j
public class SessionManager {

    private final File sessionFile;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public SessionManager(String botName) {
        this.sessionFile = new File("ilink-session-" + botName + ".json");
    }

    public void save(LoginContext ctx) {
        try {
            SessionData data = new SessionData(
                    ctx.getBotToken(), ctx.getUserId(), ctx.getBotId(), ctx.getBaseUrl());
            objectMapper.writeValue(sessionFile, data);
            log.info("登录会话已保存: {}", sessionFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("保存登录会话失败: {}", e.getMessage());
        }
    }

    public LoginContext load() {
        if (!sessionFile.exists()) return null;
        try {
            SessionData data = objectMapper.readValue(sessionFile, SessionData.class);
            return new LoginContext(data.botToken, data.userId, data.botId, data.baseUrl);
        } catch (Exception e) {
            log.warn("加载登录会话失败: {}", e.getMessage());
            sessionFile.delete();
            return null;
        }
    }

    public void clear() {
        if (sessionFile.exists()) sessionFile.delete();
    }

    private static class SessionData {
        public String botToken;
        public String userId;
        public String botId;
        public String baseUrl;
        public SessionData() {}
        public SessionData(String botToken, String userId, String botId, String baseUrl) {
            this.botToken = botToken;
            this.userId = userId;
            this.botId = botId;
            this.baseUrl = baseUrl;
        }
    }
}
