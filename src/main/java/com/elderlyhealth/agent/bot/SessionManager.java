package com.elderlyhealth.agent.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * bot 登录会话持久化。
 *
 * 除登录上下文外，还保存 SDK 的「会话上下文」：
 * iLink 推送需要每用户的 latest context token（(botId, userId) 维度），
 * 该 token 由用户发给 bot 的消息携带并在内存中刷新。若重启后不恢复，
 * 守护人（被动接收方，从不主动发言）的 token 会丢失，导致健康日报 /
 * 红色预警 / 定时提醒无法推送。这里随登录会话一并落盘，重启后 restore。
 *
 * 会话文件结构（JSON）：
 * { botToken, userId, botId, baseUrl, updatesCursor, contextTokens: { userId: token } }
 */
@Slf4j
public class SessionManager {

    private final File sessionFile;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public SessionManager(String botName) {
        this.sessionFile = new File("ilink-session-" + botName + ".json");
    }

    /** 登录成功后调用：保存登录上下文 + 当前 SDK 会话上下文（含各用户 context token）。 */
    public void saveWithContext(LoginContext ctx, ILinkClient client) {
        try {
            SessionData data = new SessionData(
                    ctx.getBotToken(), ctx.getUserId(), ctx.getBotId(), ctx.getBaseUrl());
            fillContext(data, client);
            objectMapper.writeValue(sessionFile, data);
            log.info("登录会话已保存(含{}个用户上下文): {}",
                    data.contextTokens == null ? 0 : data.contextTokens.size(),
                    sessionFile.getAbsolutePath());
        } catch (Exception e) {
            log.warn("保存完整会话失败，退回仅登录: {}", e.getMessage());
            save(ctx);
        }
    }

    /** 登录成功后（无 client 快照场景）保存基础登录信息（兼容保留）。 */
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

    /** 运行时刷新：把最新 SDK 上下文写盘（消息轮询后调用，防止重启丢 token）。 */
    public void refreshContext(ILinkClient client) {
        if (!sessionFile.exists()) return;
        try {
            SessionData data = objectMapper.readValue(sessionFile, SessionData.class);
            fillContext(data, client);
            objectMapper.writeValue(sessionFile, data);
        } catch (Exception e) {
            log.warn("刷新会话上下文失败: {}", e.getMessage());
        }
    }

    private void fillContext(SessionData data, ILinkClient client) {
        try {
            ResumeContext resume = client.exportResumeContext();
            if (resume == null) return;
            data.updatesCursor = resume.getUpdatesCursor();
            Map<String, String> tokens = new LinkedHashMap<>();
            if (resume.getConversationContextMap() != null) {
                for (Map.Entry<String, ConversationContext> e : resume.getConversationContextMap().entrySet()) {
                    ConversationContext cc = e.getValue();
                    if (cc != null && cc.hasContextToken()) {
                        tokens.put(e.getKey(), cc.getLatestContextToken());
                    }
                }
            }
            data.contextTokens = tokens;
        } catch (Exception e) {
            log.warn("导出 SDK 会话上下文失败: {}", e.getMessage());
        }
    }

    /** 读取登录上下文（不含用户 token），兼容旧文件。 */
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

    /** 读取完整 ResumeContext（登录上下文 + 各用户 context token），供 SDK restore。 */
    public ResumeContext loadResumeContext() {
        if (!sessionFile.exists()) return null;
        try {
            SessionData data = objectMapper.readValue(sessionFile, SessionData.class);
            LoginContext login = new LoginContext(data.botToken, data.userId, data.botId, data.baseUrl);
            if (data.contextTokens == null || data.contextTokens.isEmpty()) {
                return ResumeContext.of(login);
            }
            Map<String, ConversationContext> contexts = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : data.contextTokens.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) continue;
                ContextKey key = new ContextKey(login.getBotId(), e.getKey());
                ConversationContext cc = new ConversationContext(key);
                cc.setLatestContextToken(e.getValue().trim());
                contexts.put(e.getKey(), cc);
            }
            log.info("恢复会话上下文: 用户 {} 个", contexts.size());
            return ResumeContext.builder(login)
                    .updatesCursor(data.updatesCursor)
                    .conversationContexts(contexts)
                    .build();
        } catch (Exception e) {
            log.warn("加载完整会话失败: {}", e.getMessage());
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
        public String updatesCursor;
        public Map<String, String> contextTokens = new LinkedHashMap<>();
        public SessionData() {}
        public SessionData(String botToken, String userId, String botId, String baseUrl) {
            this.botToken = botToken;
            this.userId = userId;
            this.botId = botId;
            this.baseUrl = baseUrl;
        }
    }
}
