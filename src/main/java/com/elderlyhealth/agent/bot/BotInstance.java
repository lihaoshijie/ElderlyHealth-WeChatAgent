package com.elderlyhealth.agent.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

@Slf4j
public class BotInstance {

    private final String accountId;
    private final String displayName;
    private final WeChatClientRegistry registry;
    private final BiConsumer<List<WeixinMessage>, String> messageHandler;
    private final SessionManager sessionManager;
    private final ILinkConfig config;
    private volatile ILinkClient client;
    private volatile boolean running;

    public BotInstance(String displayName, WeChatClientRegistry registry,
                       BiConsumer<List<WeixinMessage>, String> messageHandler) {
        this.accountId = UUID.randomUUID().toString();
        this.displayName = displayName;
        this.registry = registry;
        this.messageHandler = messageHandler;
        this.sessionManager = new SessionManager(displayName);
        this.config = ILinkConfig.builder()
                .connectTimeoutMs(35000).readTimeoutMs(35000).writeTimeoutMs(35000)
                .heartbeatEnabled(true).heartbeatIntervalMs(5000).channelVersion("1.0.0").build();
    }

    public String getAccountId() { return accountId; }
    public String getDisplayName() { return displayName; }
    public boolean isRunning() { return running; }
    public ILinkClient getClient() { return client; }

    public void start() {
        ResumeContext saved = sessionManager.loadResumeContext();
        if (saved != null) startWithSession(saved);
        else startWithQR();
    }

    private void startWithSession(ResumeContext resumeContext) {
        client = ILinkClient.builder().config(config).resumeContext(resumeContext)
                .onMessage((OnMessageListener) messages -> {
                    messageHandler.accept(messages, accountId);
                    persistContextQuietly();
                })
                .build();
        registry.register(accountId, client);
        running = true;
        log.info("[{}] 自动恢复登录成功（上下文恢复 {} 个用户）", displayName,
                resumeContext.getConversationContextMap() == null ? 0 : resumeContext.getConversationContextMap().size());
    }

    private void startWithQR() {
        client = ILinkClient.builder().config(config)
                .onLogin(new OnLoginListener() {
                    public void onLoginSuccess(LoginContext ctx) {
                        log.info("[{}] 扫码登录成功, botId={}", displayName, ctx.getBotId());
                        sessionManager.saveWithContext(ctx, client);
                        registry.register(accountId, client);
                        running = true;
                    }
                    public void onLoginFailure(Throwable t) { log.error("[{}] 登录失败", displayName, t); }
                })
                .onMessage((OnMessageListener) messages -> {
                    messageHandler.accept(messages, accountId);
                    persistContextQuietly();
                })
                .build();
        try {
            String qr = client.executeLogin();
            System.out.println("=== Bot [" + displayName + "] 请扫码 ===");
            System.out.println(qr);
            running = true;
        } catch (Exception e) { log.error("[{}] 启动失败", displayName, e); }
    }

    /** 每条消息处理后把最新 context token 落盘，防止进程重启后守护推送丢 token。 */
    private void persistContextQuietly() {
        try {
            ILinkClient c = client;
            if (c != null) sessionManager.refreshContext(c);
        } catch (Exception e) {
            log.warn("[{}] 持久化会话上下文失败: {}", displayName, e.getMessage());
        }
    }

    public void stop() {
        running = false;
        registry.unregister(accountId);
        if (client != null) {
            try { sessionManager.refreshContext(client); } catch (Exception ignored) {}
            client.close();
        }
    }
}
