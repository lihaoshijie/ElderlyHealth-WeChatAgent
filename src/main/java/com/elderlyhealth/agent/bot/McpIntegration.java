package com.elderlyhealth.agent.bot;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class McpIntegration {

    private final ToolRegistry toolRegistry;
    private final Gson gson = new Gson();
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile boolean ready;

    public McpIntegration(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("MCP集成开始...");
        try {
            ProcessBuilder pb = new ProcessBuilder("D:\\npx.cmd", "-y", "12306-mcp");
            pb.redirectErrorStream(true);
            process = pb.start();
            log.info("MCP进程已启动, pid={}", process.pid());
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            // MCP init - 跳过横幅消息
            send("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("{")) break;
            }
            log.info("MCP init响应: {}", line);

            // Request tools
            send("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"params\":{},\"id\":2}");
            String resp = null;
            while ((resp = reader.readLine()) != null) {
                if (resp.startsWith("{")) break;
            }
            log.info("MCP raw tools/list: {}", resp);
            if (resp == null) { log.error("MCP tools/list 无响应"); return; }

            JsonObject root = gson.fromJson(resp, JsonObject.class);
            JsonElement resultEl = root.get("result");
            if (resultEl == null || !resultEl.isJsonObject()) {
                log.error("MCP result 错误: {}", resp); return;
            }
            JsonElement toolsEl = resultEl.getAsJsonObject().get("tools");
            if (toolsEl == null || !toolsEl.isJsonArray()) {
                log.error("MCP tools 错误: {}", resp); return;
            }
            JsonArray toolList = toolsEl.getAsJsonArray();
            if (toolList == null) return;

            for (var t : toolList) {
                JsonObject tool = t.getAsJsonObject();
                String name = tool.get("name").getAsString();
                String desc = tool.has("description") ? tool.get("description").getAsString() : "";

                JsonObject params = new JsonObject();
                params.addProperty("type", "object");
                JsonObject props = new JsonObject();
                JsonArray required = new JsonArray();

                JsonObject schema = tool.getAsJsonObject("inputSchema");
                if (schema != null && schema.has("properties")) {
                    JsonObject sp = schema.getAsJsonObject("properties");
                    for (String key : sp.keySet()) {
                        JsonObject p = sp.getAsJsonObject(key);
                        JsonObject prop = new JsonObject();
                        prop.addProperty("type", p.has("type") ? p.get("type").getAsString() : "string");
                        prop.addProperty("description", p.has("description") ? p.get("description").getAsString() : "");
                        props.add(key, prop);
                        required.add(new JsonPrimitive(key));
                    }
                }
                params.add("properties", props);
                params.add("required", required);

                ToolFunction tf = ToolFunction.builder()
                        .function(FunctionDefinition.builder().name(name).description(desc).parameters(params).build())
                        .build();

                toolRegistry.registerExternal(name, tf, (fn, args, uid) -> callMcp(fn, args));
                log.info("MCP工具已注册: {}", name);
            }
            ready = true;
            log.info("MCP集成完成，{} 个工具", toolList.size());
        } catch (Exception e) {
            log.error("MCP集成失败: {}", e.getMessage(), e);
        }
    }

    // ponytail: synchronized 串行化所有 MCP 请求，12306 子进程只有一条 I/O 流，
    // 并发读写会串线（A 拿到 B 的响应）。查票是低频操作，串行执行代价可忽略。
    private synchronized String callMcp(String name, String args) {
        try {
            JsonObject callArgs = gson.fromJson(args, JsonObject.class);
            JsonObject call = new JsonObject();
            call.addProperty("jsonrpc", "2.0");
            call.addProperty("method", "tools/call");
            call.addProperty("id", System.currentTimeMillis() % 100000);
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            params.add("arguments", callArgs);
            call.add("params", params);
            send(gson.toJson(call));

            String resp = reader.readLine();
            if (resp == null) return "查询服务暂时不可用";
            JsonObject root = gson.fromJson(resp, JsonObject.class);
            JsonArray content = root.getAsJsonObject("result").getAsJsonArray("content");
            if (content != null && content.size() > 0) {
                return "【12306】" + content.get(0).getAsJsonObject().get("text").getAsString();
            }
            return "【12306】查询完成";
        } catch (Exception e) {
            return "查询服务暂时不可用，请稍后重试";
        }
    }

    private void send(String json) throws IOException {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    @PreDestroy
    public void destroy() { if (process != null) process.destroy(); }
    public boolean isReady() { return ready; }
}
