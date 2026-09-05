package com.Myself.demo.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Calls FlyAI's official CLI, which proxies requests to Fliggy MCP.
 * The CLI returns structured JSON with real-time travel inventory and booking links.
 */
@Slf4j
@Component
public class FliggyTravelTool {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final String apiKey;
    private final String command;
    private final long timeoutSeconds;

    public FliggyTravelTool(
            @Value("${flyai.api-key:}") String apiKey,
            @Value("${flyai.command:flyai}") String command,
            @Value("${flyai.timeout-seconds:45}") long timeoutSeconds) {
        this.apiKey = apiKey;
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Tool(name = "fliggy_travel_search", value = "实时查询飞猪旅行产品")
    public String search(
            @P("旅行查询需求") String query) {
        if (query == null || query.trim().isEmpty()) {
            return "请提供要查询的旅行需求，例如出发地、目的地、日期、预算或酒店/景区偏好。";
        }

        Process process = null;
        long start = System.currentTimeMillis();
        try {
            // No shell is used: the query is passed as a single argument, avoiding command injection.
            ProcessBuilder builder = new ProcessBuilder(List.of(command, "ai-search", "--query", query.trim()));
            builder.redirectErrorStream(true);
            if (apiKey != null && !apiKey.isBlank()) {
                builder.environment().put("FLYAI_API_KEY", apiKey.trim());
            }

            process = builder.start();
            Process runningProcess = process;
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                    () -> readOutput(runningProcess.getInputStream()));

            if (!process.waitFor(Math.max(timeoutSeconds, 1), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "飞猪旅行查询超时，请稍后重试。";
            }

            String output = outputFuture.get(5, TimeUnit.SECONDS).trim();
            log.info("fliggy_travel_search 完成, 耗时={}ms, outputLen={}", System.currentTimeMillis() - start, output.length());
            if (process.exitValue() != 0) {
                log.warn("FlyAI CLI failed: exitCode={}, output={}", process.exitValue(), output);
                return "飞猪旅行查询失败：" + (output.isEmpty() ? "飞猪 CLI 未返回错误详情。" : output);
            }
            if (output.isEmpty()) {
                return "飞猪旅行查询未返回结果，请调整目的地、日期或筛选条件后重试。";
            }
            return output;
        } catch (IOException e) {
            log.warn("FlyAI CLI is unavailable", e);
            return "飞猪旅行工具尚未安装。请先安装官方 CLI：npm i -g @fly-ai/flyai-cli；然后重启应用。";
        } catch (Exception e) {
            log.error("FlyAI travel search failed", e);
            return "飞猪旅行查询失败，请稍后重试。";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readOutput(InputStream inputStream) {
        try (inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            boolean truncated = false;
            while ((read = inputStream.read(buffer)) != -1) {
                int remaining = MAX_OUTPUT_BYTES - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
                truncated |= read > remaining;
            }
            String result = output.toString(StandardCharsets.UTF_8);
            return truncated ? result + "\n[结果过长，已截断]" : result;
        } catch (IOException e) {
            throw new IllegalStateException("读取 FlyAI CLI 输出失败", e);
        }
    }
}
