package com.Myself.demo.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
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
    private ILinkClient client;
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
        LoginContext saved = sessionManager.load();
        if (saved != null) startWithSession(saved);
        else startWithQR();
    }

    private void startWithSession(LoginContext saved) {
        client = ILinkClient.builder().config(config).loginContext(saved)
                .onMessage((OnMessageListener) messages -> messageHandler.accept(messages, accountId)).build();
        registry.register(accountId, client);
        running = true;
        log.info("[{}] 自动恢复登录成功", displayName);
    }

    private void startWithQR() {
        client = ILinkClient.builder().config(config)
                .onLogin(new OnLoginListener() {
                    public void onLoginSuccess(LoginContext ctx) {
                        log.info("[{}] 扫码登录成功, botId={}", displayName, ctx.getBotId());
                        sessionManager.save(ctx);
                        registry.register(accountId, client);
                        running = true;
                    }
                    public void onLoginFailure(Throwable t) { log.error("[{}] 登录失败", displayName, t); }
                })
                .onMessage((OnMessageListener) messages -> messageHandler.accept(messages, accountId)).build();
        try {
            String qr = client.executeLogin();
            System.out.println("=== Bot [" + displayName + "] 请扫码 ===");
            System.out.println(qr);
            running = true;
        } catch (Exception e) { log.error("[{}] 启动失败", displayName, e); }
    }

    public void stop() {
        running = false;
        registry.unregister(accountId);
        if (client != null) client.close();
    }
}
