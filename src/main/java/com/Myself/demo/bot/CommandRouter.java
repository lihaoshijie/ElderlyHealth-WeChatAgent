package com.Myself.demo.bot;

import com.Myself.demo.mapper.AdminMapper;
import com.Myself.demo.bot.skill.Skill;
import com.Myself.demo.bot.skill.SkillManager;
import com.Myself.demo.bot.cli.Command;
import com.Myself.demo.entity.Task;
import com.Myself.demo.exception.BusinessException;
import com.Myself.demo.service.*;
import com.Myself.demo.service.LlmService.LlmResult;
import com.alibaba.dashscope.tools.ToolFunction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CommandRouter {

    @Autowired
    private RagPipelineService ragPipelineService;

    private static final int MAX_TOOL_ROUNDS = 8;
    private static final long TOKEN_BUDGET = 20000;
    private static final long ROUND_TIMEOUT_MS = 30000;
    private static final long TOTAL_TIMEOUT_MS = 300000;

    @Value("${llm.tool-result-max-chars:10000}")
    private int toolResultMaxChars;

    private final Map<String, Command> commands;
    private final ChatService chatService;
    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    private final SkillManager skillManager;
    private final AdminMapper adminMapper;
    private final TaskManager taskManager;
    private final RagService ragService;

    public CommandRouter(List<Command> commandList, ChatService chatService,
                         LlmService llmService, @Lazy ToolRegistry toolRegistry,
                         SkillManager skillManager, AdminMapper adminMapper, TaskManager taskManager,
                         RagService ragService) {
        this.commands = commandList.stream()
                .collect(Collectors.toMap(Command::getName, Function.identity()));
        this.chatService = chatService;
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.skillManager = skillManager;
        this.adminMapper = adminMapper;
        this.taskManager = taskManager;
        this.ragService = ragService;
        this.objectMapper = new ObjectMapper();
    }

    public boolean hasCommand(String name) {
        return commands.containsKey(name.toLowerCase());
    }

    public String route(String input, String userId) {
        if (input == null || input.trim().isEmpty()) return "请输入命令";

        String trimmed = input.trim();

        // 断点恢复
        if (trimmed.equals("继续") || trimmed.equals("接着") || trimmed.contains("恢复任务")) {
            Task task = taskManager.getActiveTask(userId);
            if (task != null) {
                log.info("检测到进行中的任务: userId={}, taskId={}", userId, task.getTaskId());
                return "你有一个进行中的任务，请继续输入指令完成它。";
            }
            return "没有可恢复的任务。";
        }
        if (trimmed.equals("停") || trimmed.equals("暂停") || trimmed.equals("取消")) {
            Task task = taskManager.getActiveTask(userId);
            if (task != null) {
                taskManager.failTask(task.getTaskId());
            }
            return "任务已取消。";
        }
        // 新任务开始前清除旧任务（DB挂了不阻塞消息处理）
        try {
            Task existingTask = taskManager.getActiveTask(userId);
            if (existingTask != null) {
                taskManager.failTask(existingTask.getTaskId());
            }
        } catch (Exception e) {
            log.debug("任务管理不可用(DB连接池重启中): {}", e.getMessage());
        }
        String[] parts = trimmed.split("\\s+", 2);
        String cmdName = parts[0].toLowerCase();

        Command cmd = commands.get(cmdName);
        if (cmd != null) {
            String[] args = parts.length > 1 ? new String[]{parts[1]} : new String[0];
            return executeCommand(cmd, cmdName, args);
        }

        log.info("未匹配直接命令，LLM function calling: {}", compactForHistory(trimmed));
        List<Map<String, String>> history = chatService.getHistory(userId);
        // 过滤历史中被误存的预告型回复（"正在为您…请稍候"），防止 LLM 模仿后反复只回预告
        history = history.stream()
                .filter(m -> !("assistant".equals(m.get("role")) && isStallReply(m.get("content"))))
                .collect(java.util.stream.Collectors.toList());
        List<ToolFunction> tools = toolRegistry.filterToolsByInput(trimmed);
        String taskId = java.util.UUID.randomUUID().toString().substring(0, 8);

        JsonArray messages = llmService.buildBaseMessages(userId, history);
        com.google.gson.JsonObject userMsg = new com.google.gson.JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", trimmed);
        messages.add(userMsg);

        String systemPrompt = messages.get(0).getAsJsonObject().get("content").getAsString();

        String ragContext;
        try {
            ragContext = ragPipelineService.buildContext(userId,trimmed, 3);
        } catch (Exception e) {
            log.warn("RAG 检索失败，跳过知识库: {}", e.getMessage());
            ragContext = "";
        }
        String basePrompt=systemPrompt;
        if (!ragContext.isEmpty()) {
            log.info("RAG context 注入成功, 长度={}", ragContext.length());
            String injected=systemPrompt+"\n\n"+ragContext + "\n请基于以上知识回答用户问题。如果知识库内容与问题无关，请直接回复：抱歉，知识库暂无相关内容。";
            messages.get(0).getAsJsonObject().addProperty("content", injected);
        } else {
            log.info("RAG context 为空，未注入");
        }
        java.util.LinkedHashMap<String, Skill> activeSkills = new java.util.LinkedHashMap<>();
        long tokensUsed = 0;
        long startTime = System.currentTimeMillis();


        LlmResult result = llmService.chatRaw(messages, tools);
        Set<String> calledKeys = java.util.Collections.synchronizedSet(new HashSet<>());

        // 无工具调用 → 直接返回，不再二次调 LLM
        if (result.isText()) {
            String reply = result.getContent();
            // 预告文本兜底：LLM 只回了"正在为您…请稍候"这类提示而未调用工具，
            // 把预告作为 assistant 消息回填，再要求它必须调用工具继续完成（最多重试2次）
            int stallRetries = 0;
            while (isStallReply(reply) && tools != null && !tools.isEmpty() && stallRetries < 2) {
                stallRetries++;
                log.warn("检测到预告型回复(第{}次)，强制继续工具调用: {}", stallRetries, reply);
                com.google.gson.JsonObject stallMsg = new com.google.gson.JsonObject();
                stallMsg.addProperty("role", "assistant");
                stallMsg.addProperty("content", reply);
                messages.add(stallMsg);
                com.google.gson.JsonObject nudge = new com.google.gson.JsonObject();
                nudge.addProperty("role", "user");
                nudge.addProperty("content",
                        "请立即调用相关工具完成上面的请求，不要回复“正在为您…”“请稍候”等提示语。"
                        + "等所有工具执行完毕后再给出最终答复。");
                messages.add(nudge);
                result = llmService.chatRaw(messages, tools);
                if (result.isFunctionCall()) break;
                reply = result.getContent();
            }
            if (result.isText()) {
                reply = result.getContent();
                reply = selfCheck(reply, trimmed, userId, history);
                forcePatchMissingCalls(reply, trimmed, userId);
                chatService.addHistory(userId, compactForHistory(trimmed), reply);
                return reply;
            }
        }

        int round = 0;

        while (result.isFunctionCall()) {
            // ... existing tool execution loop ...
            List<LlmResult.FunctionCall> calls = result.getFunctionCalls();
            log.info("第{}轮工具调用: {}个", round + 1, calls.size());

            for (int i = 0; i < calls.size(); i++) {
                log.info("  → {}({})", calls.get(i).name(),
                        calls.get(i).args().length() > 80
                                ? calls.get(i).args().substring(0, 80) + "..."
                                : calls.get(i).args());
            }

            List<String> toolResults = java.util.Collections.synchronizedList(new ArrayList<>());
            List<LlmResult.FunctionCall> executedCalls = java.util.Collections.synchronizedList(new ArrayList<>());

            if (calls.size() <= 1) {
                for (LlmResult.FunctionCall call : calls) {
                    String key = call.name() + "|" + call.args();
                    if (!calledKeys.add(key)) {
                        log.warn("重复调用拦截: {}", key);
                        toolResults.add("[已拦截：该工具和参数已被调用过]");
                        executedCalls.add(call);
                        continue;
                    }

                    String toolResult = executeCall(call, userId);
                    toolResults.add(toolResult != null ? toolResult : "工具执行失败");
                    executedCalls.add(call);
                }
            } else {
                // 并行执行：预填充占位
                for (int i = 0; i < calls.size(); i++) toolResults.add(null);
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (int i = 0; i < calls.size(); i++) {
                    final int idx = i;
                    final LlmResult.FunctionCall call = calls.get(i);
                    futures.add(CompletableFuture.runAsync(() -> {
                        String key = call.name() + "|" + call.args();
                        if (!calledKeys.add(key)) {
                            log.warn("重复调用拦截: {}", key);
                            toolResults.set(idx, "[已拦截：该工具和参数已被调用过]");
                            executedCalls.add(call);
                            return;
                        }
                        String toolResult = executeCall(call, userId);
                        toolResults.set(idx, toolResult != null ? toolResult : "工具执行失败");
                        executedCalls.add(call);
                    }));
                }
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("并行工具执行异常", e);
                }
            }

            for (int i = 0; i < executedCalls.size(); i++) {
                LlmResult.FunctionCall call = executedCalls.get(i);
                String toolResult = toolResults.get(i);
                if (adminMapper != null) {
                    try {
                        adminMapper.insertTrace(userId, taskId, round, call.name(), call.args(),
                                toolResult != null ? toolResult.substring(0, Math.min(200, toolResult.length())) : "null",
                                toolResult != null && !toolResult.contains("失败") ? "done" : "failed", 0);
                    } catch (Exception ignored) {}
                }
                for (Skill skill : skillManager.getSkillsFor(call.name())) {
                    if (activeSkills.containsKey(skill.getName())) continue;
                    activeSkills.put(skill.getName(), skill);
                    log.info("激活 Skill: {}", skill.getName());
                }
            }

            if (toolResults.isEmpty()) break;

            tokensUsed += estimateTokens(messages);
            long elapsed = System.currentTimeMillis() - startTime;

            // 先把工具结果拼入消息，保证后续总结/兜底时 LLM 有真实数据可用
            messages.add(llmService.buildAssistantWithToolCalls(executedCalls));
            for (int i = 0; i < executedCalls.size(); i++) {
                messages.add(llmService.buildToolResult(i, executedCalls.get(i), truncateToolResult(toolResults.get(i))));
            }

            // 成本预算保护
            if (tokensUsed > TOKEN_BUDGET) {
                log.warn("Token预算耗尽({})，强制总结", TOKEN_BUDGET);
                String summary = streamWithStallGuard(messages, basePrompt);
                chatService.addHistory(userId, compactForHistory(trimmed), summary);
                return summary;
            }

            // 超时保护
            if (elapsed > TOTAL_TIMEOUT_MS) {
                log.warn("总超时({}s)，强制总结", TOTAL_TIMEOUT_MS / 1000);
                String summary = streamWithStallGuard(messages, basePrompt);
                chatService.addHistory(userId, compactForHistory(trimmed), summary);
                return summary;
            }

            round++;
            if (round >= MAX_TOOL_ROUNDS) {
                log.warn("达到最大轮次上限({})，流式回复", MAX_TOOL_ROUNDS);
                injectSkills(messages, basePrompt, activeSkills);
                warnBudget(messages, basePrompt, tokensUsed);
                String streamReply = streamWithStallGuard(messages, basePrompt);
                if (!streamReply.isEmpty()) {
                    forcePatchMissingCalls(streamReply, trimmed, userId);
                    chatService.addHistory(userId, compactForHistory(trimmed), streamReply);
                    return streamReply;
                }
                break;
            }

            injectSkills(messages, basePrompt, activeSkills);
            warnBudget(messages, basePrompt, tokensUsed);
            result = llmService.chatRaw(messages, tools);

        }

        if (result.isText()) {
            String reply = result.getContent();
            forcePatchMissingCalls(reply, trimmed, userId);
            chatService.addHistory(userId, compactForHistory(trimmed), reply);
            return reply;
        }

        return "无法处理此消息";
    }

    private static final String[] STALL_PATTERNS = {
            "正在为您", "正在为你", "正在整理", "正在处理", "正在提取", "正在准备",
            "正在查询", "正在搜索", "正在生成", "正在汇总", "请稍候", "请稍等",
            "稍等片刻", "这就为您", "马上为您", "稍后为您", "我会尽快", "让我先",
            "我先帮您", "先帮您", "待我", "整理中", "处理中", "查询中", "搜索中",
            "我需要先", "我先获取", "先获取当前", "获取当前时间", "先调", "先查询一下时间"
    };

    private boolean isStallReply(String reply) {
        if (reply == null || reply.isEmpty()) return false;
        String r = reply.trim();
        if (r.length() > 80) return false;
        for (String p : STALL_PATTERNS) {
            if (r.contains(p)) return true;
        }
        return false;
    }

    /** 流式兜底回复：若 LLM 只回预告型文案（如"我需要先…"），追加指令重试一次，直接给出结果 */
    private String streamWithStallGuard(JsonArray messages, String basePrompt) {
        String reply = llmService.chatStream(messages, null);
        if (!isStallReply(reply)) return reply;
        log.warn("兜底回复为预告型，重试: {}", reply);
        com.google.gson.JsonObject nudge = new com.google.gson.JsonObject();
        nudge.addProperty("role", "user");
        nudge.addProperty("content", "不要回复『正在为您…』『我需要先…』等预告语，请直接根据已获得的信息给出最终答案。");
        messages.add(nudge);
        return llmService.chatStream(messages, null);
    }

    private void forcePatchMissingCalls(String reply, String userInput, String userId) {
        if (ToolExecutionContext.hasPending()) return;

        boolean wantsQr = userInput.contains("官网") || userInput.contains("网址")
                || userInput.contains("公司") || userInput.contains("二维码")
                || userInput.contains("扫码");

        if (wantsQr) {
            // 仅从回复文本提取 URL,不再对纯品牌名自动搜索官网
            String url = extractUrlFromText(reply);
            if (url != null) {
                log.info("补调 qr_code: {}", url);
                ToolExecutionContext.recordQrCode(url);
                return;
            }
            log.info("未找到可补的二维码 URL，跳过补调");
        }

        // 用户说了"发短信"但 LLM 没调
        if (userInput.contains("发短信") || userInput.contains("短信") || userInput.contains("sms")) {
            if (reply.matches(".*1[3-9]\\d{9}.*")) {
            String phone = reply.replaceAll(".*?(1[3-9]\\d{9}).*", "$1");
            String body = userInput.contains("祝福") ? "祝你生活愉快，万事如意！" : "来自MyDemo Bot的消息";
            log.info("补调 qr_code(sms): {} -> {}", phone, body);
            ToolExecutionContext.recordQrCode("sms:" + phone + "?body=" + body);
            }
        }
    }

    private String selfCheck(String reply, String userInput, String userId, List<Map<String, String>> history) {
        if (reply == null || reply.isEmpty()) return "抱歉，我没理解你的意思，可以再说详细一点吗？";
        // 简单招呼/确认类回复无需检查
        if (reply.matches("^[你好哈嘿哎哦噢嗯啊呀]+[！。，？~～]?$")) return reply;
        boolean needCheck = reply.contains("可能") || reply.contains("也许")
                || reply.contains("已经生成") || reply.contains("请稍候") || reply.contains("抱歉，我没");
        if (!needCheck) return reply;
        try {
            var msgs = llmService.buildBaseMessages(userId, history);
            var um = new com.google.gson.JsonObject();
            um.addProperty("role", "user");
            um.addProperty("content", "用户问：「" + userInput + "」。你刚才回复了：「" + reply + 
                    "」。请检查这个回复是否准确、完整。如果有问题请重新回答，没问题直接回复「OK」。");
            msgs.add(um);
            String checked = llmService.chatStream(msgs, null);
            if (checked == null || checked.isEmpty() || checked.contains("OK")) return reply;
            log.info("自检优化了回复");
            return checked;
        } catch (Exception e) {
            return reply;
        }
    }

    private long estimateTokens(JsonArray messages) {
        try {
            return llmService.countTokens(messages);
        } catch (Exception e) {
            log.warn("estimateTokens异常", e);
            return 1000;
        }
    }

    private void warnBudget(JsonArray messages, String basePrompt, long used) {
        if (used > TOKEN_BUDGET * 0.7) {
            long remaining = TOKEN_BUDGET - used;
            String warning = basePrompt + "\n\n【成本警告】本轮已消耗" + (used * 3 / 2)
                    + "字符，预算剩余约" + (remaining * 3 / 2) + "字符。请精简回复，避免过多工具调用。";
            messages.get(0).getAsJsonObject().addProperty("content", warning);
        }
    }



    private String extractUrlFromText(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(https?://[\\w./?=&%#@!$^*+\\-]+)")
                .matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static final int MAX_RETRIES = 3;

    private String executeCall(LlmResult.FunctionCall call, String userId) {
        String fnName = call.name();
        String fnArgs = call.args();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String result = executeOnce(fnName, fnArgs, userId);
                if (attempt > 1) log.info("工具 {} 第{}次重试成功", fnName, attempt);
                return result;
            } catch (Exception e) {
                boolean canRetry = isTransient(e);
                if (attempt < MAX_RETRIES && canRetry) {
                    log.warn("工具 {} 执行失败(瞬时错误)，第{}次重试: {}", fnName, attempt, e.getMessage());
                    try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
                } else {
                    int retries = attempt - 1;
                    log.error("工具 {} 执行失败: {}", fnName, e.getMessage());
                    // 自动降级
                    String degraded = autoDegrade(fnName, fnArgs, userId);
                    if (degraded != null) return degraded;
                    return ErrorRouter.userMessage(ErrorRouter.classify(new RuntimeException(e.getMessage())));
                }
            }
        }
        return "操作失败，已达最大重试次数";
    }

    private String executeOnce(String fnName, String fnArgs, String userId) {
        Command fnCmd = commands.get(fnName);
        if (fnCmd != null) {
            String[] cmdArgs = extractArgs(fnArgs);
            return executeCommand(fnCmd, fnName, cmdArgs);
        }
        try {
            return toolRegistry.execute(fnName, fnArgs, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String autoDegrade(String fnName, String fnArgs, String userId) {
        String alt = null;
        switch (fnName) {
            case "baidu_search": alt = "google_search"; break;
            case "google_search": alt = "baidu_search"; break;
            case "ai_news": alt = "baidu_search"; break;
            case "network_hot": alt = "baidu_search"; break;
        }
        if (alt == null) return null;
        log.info("自动降级: {} → {}", fnName, alt);
        try {
            String result = toolRegistry.execute(alt, fnArgs, userId);
            return "（已自动切换" + alt + "）" + result;
        } catch (Exception e) {
            log.warn("降级也失败: {} → {}", fnName, alt);
            return null;
        }
    }

    private boolean isTransient(Exception e) {
        return e instanceof java.io.IOException
                || e instanceof java.net.SocketTimeoutException
                || e.getMessage() != null && (e.getMessage().contains("timeout")
                        || e.getMessage().contains("connect")
                        || e.getMessage().contains("Connection refused"));
    }

    private String[] extractArgs(String fnArgs) {
        try {
            if (fnArgs == null || fnArgs.trim().isEmpty()) {
                return new String[0];
            }
            JsonNode argsNode = objectMapper.readTree(fnArgs);
            String city = argsNode.has("city") ? argsNode.get("city").asText() : "";
            int days = argsNode.has("days") ? argsNode.get("days").asInt(1) : 1;
            if (!city.isEmpty()) {
                return new String[]{city, String.valueOf(days)};
            }
            return new String[0];
        } catch (Exception e) {
            log.warn("解析函数参数失败: {}", fnArgs, e);
            return new String[0];
        }
    }

    private String executeCommand(Command cmd, String cmdName, String[] args) {
        try {
            return cmd.execute(args);
        } catch (BusinessException e) {
            log.warn("业务异常: {}", e.getMessage());
            return "[错误] " + e.getMessage();
        } catch (Exception e) {
            log.error("命令执行异常: {}", cmdName, e);
            return "[错误] 系统异常";
        }
    }

    private void injectSkills(JsonArray messages, String basePrompt, java.util.LinkedHashMap<String, Skill> activeSkills) {
        if (activeSkills.isEmpty()) return;
        StringBuilder sb = new StringBuilder(basePrompt);
        for (Skill skill : activeSkills.values()) {
            sb.append("\n\n").append(skill.getRule());
        }
        messages.get(0).getAsJsonObject().addProperty("content", sb.toString());
    }

    private String truncateToolResult(String result) {
        if (result == null) return null;
        int max = toolResultMaxChars;
        if (max <= 0 || result.length() <= max) return result;
        int head = max * 3 / 4;
        int tail = Math.max(0, max - head - 40);
        return result.substring(0, head)
                + "\n...(已截断约" + (result.length() - max) + "字符)...\n"
                + (tail > 0 ? result.substring(result.length() - tail) : "");
    }

    /** 历史存储/日志展示用的压缩文本：文件上传场景只保留文件名，超长内容截断 */
    private String compactForHistory(String text) {
        if (text == null) return null;
        if (text.length() <= 200) return text;
        String lower = text.toLowerCase();
        int idx = lower.indexOf("用户上传了一个文件(");
        if (idx >= 0) {
            int end = text.indexOf(")", idx);
            if (end > idx) {
                return text.substring(0, idx) + "用户上传了一个文件("
                        + text.substring(idx + "用户上传了一个文件(".length(), end) + ")";
            }
        }
        return text.substring(0, 200) + "...(内容过长已省略)";
    }
}
