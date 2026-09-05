package com.elderlyhealth.agent.bot;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@Component
public class BotManager {

    private final Map<String, BotInstance> bots = new ConcurrentHashMap<>();
    private BiConsumer<List<WeixinMessage>, String> messageHandler;
    private final WeChatClientRegistry registry;

    public BotManager(WeChatClientRegistry registry) {
        this.registry = registry;
    }

    public void setMessageHandler(BiConsumer<List<WeixinMessage>, String> handler) { this.messageHandler = handler; }

    public BotInstance create(String displayName) {
        BotInstance bot = new BotInstance(displayName, registry, messageHandler);
        bots.put(bot.getAccountId(), bot);
        return bot;
    }

    public void start(String displayName) { create(displayName).start(); }
    public BotInstance get(String id) { return bots.get(id); }
    public Collection<BotInstance> list() { return bots.values(); }

    public void stopAll() { bots.values().forEach(BotInstance::stop); bots.clear(); }
}
