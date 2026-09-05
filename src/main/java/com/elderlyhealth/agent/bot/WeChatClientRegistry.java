package com.elderlyhealth.agent.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WeChatClientRegistry {
    private final Map<String, ILinkClient> registry = new ConcurrentHashMap<>();

    public void register(String accountId, ILinkClient client) { registry.put(accountId, client); }
    public void unregister(String accountId) { registry.remove(accountId); }
    public ILinkClient get(String accountId) { return registry.get(accountId); }
    public Map<String, ILinkClient> all() { return registry; }
}
