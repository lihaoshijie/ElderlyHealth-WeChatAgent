package com.Myself.demo.service;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LlmService {

    private final String model;
    private final String systemPrompt;
    private final MemoryService memoryService;
    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingModel;
    private final OpenAiTokenizer tokenizer;
    private final Gson gson = new Gson();

    public LlmService(
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.model}") String model,
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.system-prompt}") String systemPrompt,
            @Value("${llm.log-traffic:false}") boolean logTraffic,
            MemoryService memoryService) {
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.memoryService = memoryService;

        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(1)
                .logRequests(logTraffic)
                .logResponses(logTraffic)
                .build();

        this.streamingModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(180))
                .logRequests(logTraffic)
                .logResponses(logTraffic)
                .build();

        this.tokenizer = new OpenAiTokenizer(model);

        log.info("LlmService 初始化完成 (LangChain4j), model={}", model);
    }

    private String buildSystemPrompt(String userId) {
        String facts = memoryService.getFormattedFacts(userId);
        return facts.isEmpty() ? systemPrompt : systemPrompt + "\n\n用户信息：" + facts + "。";
    }

    public String chat(String userId, List<Map<String, String>> history) {
        try {
            List<ChatMessage> messages = buildMessages(userId, history);
            Response<AiMessage> resp = chatModel.generate(messages);
            return resp.content().text();
        } catch (Exception e) {
            log.error("LLM 对话调用异常", e);
            return "AI 服务暂时不可用，请稍后再试";
        }
    }

    public LlmResult chat(String userMessage, List<? extends ToolBase> tools,
                          List<Map<String, String>> history, String userId) {
        try {
            List<ChatMessage> messages = buildMessages(userId, history);
            messages.add(UserMessage.from(userMessage));
            List<ToolBase> tb = (tools != null) ? new ArrayList<>(tools) : null;
            return chatRaw(messages, tb);
        } catch (Exception e) {
            log.error("LLM 调用异常", e);
            return LlmResult.text("AI 服务暂时不可用，请稍后再试");
        }
    }

    public LlmResult chatRaw(JsonArray messages, List<? extends ToolBase> tools) {
        List<ChatMessage> msgs = fromJsonArray(messages);
        List<ToolBase> tb = new ArrayList<>();
        if (tools != null) tb.addAll(tools);
        return chatRaw(msgs, tb);
    }

    private LlmResult chatRaw(List<ChatMessage> messages, List<ToolBase> tools) {
        try {
            Response<AiMessage> resp;
            if (tools != null && !tools.isEmpty()) {
                List<ToolSpecification> specs = toToolSpecs(tools);
                resp = chatModel.generate(messages, specs);
            } else {
                resp = chatModel.generate(messages);
            }

            AiMessage aiMsg = resp.content();
            if (aiMsg.hasToolExecutionRequests()) {
                List<LlmResult.FunctionCall> calls = new ArrayList<>();
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    calls.add(new LlmResult.FunctionCall(req.name(), req.arguments()));
                }
                log.info("Function calling: {} tool(s)", calls.size());
                return LlmResult.functionCall(calls);
            }
            return LlmResult.text(aiMsg.text());
        } catch (Exception e) {
            log.error("LLM chatRaw 异常", e);
            return LlmResult.text("AI 服务暂时不可用，请稍后再试");
        }
    }

    public String chatStream(JsonArray messages, List<ToolBase> tools) {
        try {
            List<ChatMessage> msgs = fromJsonArray(messages);
            List<ToolSpecification> specs = tools != null && !tools.isEmpty() ? toToolSpecs(tools) : null;

            CompletableFuture<String> future = new CompletableFuture<>();
            StringBuilder full = new StringBuilder();

            streamingModel.generate(msgs, specs != null ? specs : List.of(),
                    new dev.langchain4j.model.StreamingResponseHandler<AiMessage>() {
                        @Override
                        public void onNext(String token) {
                            full.append(token);
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
                            future.complete(full.toString());
                        }

                        @Override
                        public void onError(Throwable error) {
                            log.error("流式调用出错", error);
                            future.complete(full.toString());
                        }
                    });

            return future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("LLM 流式调用异常", e);
            return "AI 服务暂时不可用，请稍后再试";
        }
    }

    public int countTokens(JsonArray messages) {
        try {
            List<ChatMessage> msgs = fromJsonArray(messages);
            return tokenizer.estimateTokenCountInMessages(msgs);
        } catch (IllegalArgumentException e) {
            long chars = 0;
            for (JsonElement el : messages) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("content") && !obj.get("content").isJsonNull())
                    chars += obj.get("content").getAsString().length();
            }
            return (int) (chars / 1.5);
        }
    }

    // ==================== 消息构建（不变） ====================

    public JsonArray buildBaseMessages(String userId, List<Map<String, String>> history) {
        JsonArray arr = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", buildSystemPrompt(userId));
        arr.add(sys);
        if (history != null) {
            for (var m : history) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", m.get("role"));
                msg.addProperty("content", m.get("content"));
                arr.add(msg);
            }
        }
        return arr;
    }

    public JsonObject buildAssistantWithToolCalls(List<LlmResult.FunctionCall> calls) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.add("content", null);
        JsonArray tcs = new JsonArray();
        for (int i = 0; i < calls.size(); i++) {
            LlmResult.FunctionCall call = calls.get(i);
            JsonObject tc = new JsonObject();
            tc.addProperty("id", "call_" + i + "_" + call.name());
            tc.addProperty("type", "function");
            JsonObject fn = new JsonObject();
            fn.addProperty("name", call.name());
            fn.addProperty("arguments", call.args());
            tc.add("function", fn);
            tcs.add(tc);
        }
        msg.add("tool_calls", tcs);
        return msg;
    }

    public JsonObject buildToolResult(int index, LlmResult.FunctionCall call, String result) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "tool");
        msg.addProperty("content", result);
        msg.addProperty("tool_call_id", "call_" + index + "_" + call.name());
        msg.addProperty("name", call.name());
        return msg;
    }

    // ==================== 内部转换 ====================

    private List<ChatMessage> buildMessages(String userId, List<Map<String, String>> history) {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from(buildSystemPrompt(userId)));
        if (history != null) {
            for (var m : history) {
                String role = m.get("role");
                String content = m.get("content");
                switch (role) {
                    case "user" -> msgs.add(UserMessage.from(content));
                    case "assistant" -> msgs.add(AiMessage.from(content));
                    case "tool" -> {
                        String callId = m.getOrDefault("tool_call_id", "");
                        String name = m.getOrDefault("name", "");
                        msgs.add(ToolExecutionResultMessage.from(callId, name, content));
                    }
                }
            }
        }
        return msgs;
    }

    private List<ChatMessage> fromJsonArray(JsonArray arr) {
        List<ChatMessage> msgs = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String role = obj.get("role").getAsString();
            String content = obj.has("content") && !obj.get("content").isJsonNull()
                    ? obj.get("content").getAsString() : "";
            switch (role) {
                case "system" -> msgs.add(SystemMessage.from(content));
                case "user" -> msgs.add(UserMessage.from(content));
                case "assistant" -> {
                    if (obj.has("tool_calls")) {
                        List<ToolExecutionRequest> reqs = new ArrayList<>();
                        JsonArray tcs = obj.getAsJsonArray("tool_calls");
                        for (JsonElement tcEl : tcs) {
                            JsonObject tc = tcEl.getAsJsonObject();
                            JsonObject fn = tc.getAsJsonObject("function");
                            reqs.add(ToolExecutionRequest.builder()
                                    .id(tc.get("id").getAsString())
                                    .name(fn.get("name").getAsString())
                                    .arguments(fn.get("arguments").getAsString())
                                    .build());
                        }
                        msgs.add(AiMessage.from(reqs));
                    } else {
                        msgs.add(AiMessage.from(content));
                    }
                }
                case "tool" -> {
                    String callId = obj.has("tool_call_id") ? obj.get("tool_call_id").getAsString() : "";
                    String name = obj.has("name") ? obj.get("name").getAsString() : "";
                    msgs.add(ToolExecutionResultMessage.from(callId, name, content));
                }
            }
        }
        return msgs;
    }

    private List<ToolSpecification> toToolSpecs(List<ToolBase> tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (ToolBase tool : tools) {
            if (tool instanceof ToolFunction tf) {
                FunctionDefinition fd = tf.getFunction();
                specs.add(ToolSpecification.builder()
                        .name(fd.getName())
                        .description(fd.getDescription())
                        .parameters(convertToSchema(fd.getParameters()))
                        .build());
            }
        }
        return specs;
    }

    private JsonObjectSchema convertToSchema(JsonObject gsonParams) {
        if (gsonParams == null || gsonParams.isJsonNull()) return null;
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (gsonParams.has("properties")) {
            JsonObject props = gsonParams.getAsJsonObject("properties");
            for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
                String propName = entry.getKey();
                JsonObject propDef = entry.getValue().getAsJsonObject();
                String propType = propDef.has("type") ? propDef.get("type").getAsString() : "string";
                String propDesc = propDef.has("description") ? propDef.get("description").getAsString() : "";
                switch (propType) {
                    case "integer" -> builder.addIntegerProperty(propName, propDesc);
                    case "number" -> builder.addNumberProperty(propName, propDesc);
                    case "boolean" -> builder.addBooleanProperty(propName, propDesc);
                    default -> builder.addStringProperty(propName, propDesc);
                }
            }
        }
        if (gsonParams.has("required")) {
            JsonArray req = gsonParams.getAsJsonArray("required");
            List<String> required = new ArrayList<>();
            for (JsonElement el : req) {
                required.add(el.getAsString());
            }
            builder.required(required);
        }
        return builder.build();
    }

    // ==================== LlmResult ====================

    public static class LlmResult {
        private final String type;
        private final String content;
        private final List<FunctionCall> functionCalls;

        private LlmResult(String type, String content, List<FunctionCall> functionCalls) {
            this.type = type;
            this.content = content;
            this.functionCalls = functionCalls;
        }

        public static LlmResult text(String content) {
            return new LlmResult("text", content, List.of());
        }

        public static LlmResult functionCall(List<FunctionCall> calls) {
            return new LlmResult("function_call", null, calls);
        }

        public boolean isText() { return "text".equals(type); }
        public boolean isFunctionCall() { return "function_call".equals(type); }
        public String getContent() { return content; }
        public List<FunctionCall> getFunctionCalls() { return functionCalls; }
        public String getFunctionName() { return functionCalls.isEmpty() ? null : functionCalls.get(0).name(); }
        public String getFunctionArgs() { return functionCalls.isEmpty() ? null : functionCalls.get(0).args(); }

        public record FunctionCall(String name, String args) {}
    }
}
