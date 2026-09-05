package com.Myself.demo.service;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.Myself.demo.util.FileUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Slf4j
@Service
public class ImageService {

    private final String apiKey;
    private static final String IMAGE_DIR = "src/main/resources/static/images";

    // ===== 内容安全关键词黑名单 =====
    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
        "地图", "疆域", "国界", "领土", "主权", "政治", "色情", "暴力", "血腥",
        "毒品", "枪支", "赌博", "领导人", "国家主席", "总理", "国旗", "国徽"
    );

    public ImageService(@Value("${llm.api-key}") String apiKey) {
        this.apiKey = apiKey;
        log.info("ImageService 初始化完成");
    }

    public String recognizeImage(byte[] imageBytes, String question) {
        try {
            Path tempFile = FileUtil.createTempFile("wechat_img_", ".png", imageBytes);

            String q = question != null && !question.isEmpty() ? question : "描述图片内容";

            MultiModalConversation conv = new MultiModalConversation();
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(
                            Map.of("image", tempFile.toAbsolutePath().toString()),
                            Map.of("text", q)
                    ))
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-vl-max")
                    .message(userMsg)
                    .build();

            MultiModalConversationResult result = conv.call(param);
            FileUtil.deleteTempFile(tempFile);

            String answer = extractTextResult(result);
            log.info("识图完成: question={}, answer={}", q, answer.substring(0, Math.min(50, answer.length())));
            return answer;

        } catch (Exception e) {
            log.error("识图失败", e);
            return "抱歉，图片识别失败，请稍后再试";
        }
    }

    /**
     * 生成图片 → 下载到本地 static 目录 → 返回可公开访问的相对路径。
     * 解决 OSS STS 签名链接无法在微信内展示的问题。
     */
    public String generateAndSave(String prompt) {
        // ===== 前置安全过滤 =====
        String blockWord = checkSafety(prompt);
        if (blockWord != null) {
            return "SAFETY_BLOCKED:" + blockWord;
        }

        try {
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(ImageSynthesis.Models.WANX_V1)
                    .prompt(prompt)
                    .n(1)
                    .build();

            log.info("正在调用通义万相生成图片: {}", prompt);
            ImageSynthesisResult result = new ImageSynthesis().call(param);

            String taskStatus = result.getOutput().getTaskStatus();
            if (!"SUCCEEDED".equals(taskStatus)) {
                if ("RUNNING".equals(taskStatus) || "PENDING".equals(taskStatus)) {
                    result = new ImageSynthesis().wait(result, apiKey);
                    taskStatus = result.getOutput().getTaskStatus();
                }
                if (!"SUCCEEDED".equals(taskStatus)) {
                    throw new RuntimeException("图片生成失败，状态: " + taskStatus
                            + ", 错误: " + result.getOutput().getMessage());
                }
            }

            String ossUrl = result.getOutput().getResults().get(0).get("url");
            log.info("图片生成成功，OSS地址: {}", ossUrl);

            // ===== 下载到本地 static 目录，返回无签名可公开访问的静态 URL =====
            String localPath = downloadToLocal(ossUrl, prompt);
            log.info("图片已保存到本地: {}", localPath);
            return localPath;

        } catch (Exception e) {
            log.error("生图失败", e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            // ===== 安全拦截友好提示 =====
            if (msg.contains("DataInspectionFailed") || msg.contains("inspection")) {
                return "SAFETY_BLOCKED:内容安全";
            }
            if (msg.contains("IPInfringement")) {
                return "SAFETY_BLOCKED:版权保护";
            }
            throw new RuntimeException("图片生成失败: " + msg, e);
        }
    }

    /** 保留旧接口兼容 */
    public byte[] generateImage(String prompt) {
        String localPath = generateAndSave(prompt);
        if (localPath != null && localPath.startsWith("SAFETY_BLOCKED:")) {
            String keyword = localPath.substring("SAFETY_BLOCKED:".length());
            throw new RuntimeException("内容安全拦截: " + keyword);
        }
        try {
            return Files.readAllBytes(Paths.get(localPath));
        } catch (IOException e) {
            throw new RuntimeException("读取图片失败", e);
        }
    }

    /**
     * 图片编辑（图生图）：基于用户原图 + 编辑指令，调用 wanx2.1-imageedit。
     * 生成结果下载到本地 static 目录，返回磁盘路径。
     */
    public String editImageAndSave(byte[] imageBytes, String prompt) {
        String blockWord = checkSafety(prompt);
        if (blockWord != null) {
            return "SAFETY_BLOCKED:" + blockWord;
        }

        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:image/png;base64," + base64;

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "wanx2.1-imageedit");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("function", "description_edit");
            input.put("prompt", prompt);
            input.put("base_image_url", dataUri);
            body.put("input", input);
            body.put("parameters", Map.of("n", 1));

            log.info("正在调用万相图片编辑: prompt={}, 原图大小={}KB", prompt, imageBytes.length / 1024);

            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/image-synthesis")
                    .toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-DashScope-Async", "enable");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(mapper.writeValueAsBytes(body));
            }

            String submitJson = readResponse(conn);
            log.info("图片编辑提交响应: {}", submitJson.length() > 200 ? submitJson.substring(0, 200) : submitJson);
            JsonNode submit = mapper.readTree(submitJson);
            String taskId = submit.path("output").path("task_id").asText("");
            if (taskId.isEmpty()) {
                throw new RuntimeException("图片编辑任务创建失败: "
                        + submit.path("output").path("message").asText(submitJson));
            }

            // ===== 轮询任务结果 =====
            long deadline = System.currentTimeMillis() + 120000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);

                HttpURLConnection poll = (HttpURLConnection) URI.create(
                        "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId)
                        .toURL().openConnection();
                poll.setRequestMethod("GET");
                poll.setConnectTimeout(30000);
                poll.setReadTimeout(60000);
                poll.setRequestProperty("Authorization", "Bearer " + apiKey);

                String pollJson = readResponse(poll);
                JsonNode task = mapper.readTree(pollJson);
                String status = task.path("output").path("task_status").asText("");
                log.info("图片编辑任务状态: taskId={}, status={}", taskId, status);

                if ("SUCCEEDED".equals(status)) {
                    String ossUrl = task.path("output").path("results").get(0).path("url").asText("");
                    if (ossUrl.isEmpty()) {
                        throw new RuntimeException("图片编辑成功但未返回图片地址");
                    }
                    String localPath = downloadToLocal(ossUrl, prompt);
                    log.info("图片编辑完成，已保存到本地: {}", localPath);
                    return localPath;
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                    String msg = task.path("output").path("message").asText("未知错误");
                    if (msg.contains("DataInspectionFailed") || msg.contains("inspection")) {
                        return "SAFETY_BLOCKED:内容安全";
                    }
                    throw new RuntimeException("图片编辑失败，状态: " + status + ", 错误: " + msg);
                }
            }
            throw new RuntimeException("图片编辑超时");

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片编辑失败", e);
            throw new RuntimeException("图片编辑失败: " + e.getMessage(), e);
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in != null ? in : new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String text = sb.toString();
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + text);
            }
            return text;
        }
    }

    // ===== 内部方法 =====

    /** 下载 OSS 图片到本地 static 目录，返回磁盘路径（如 src/main/resources/static/images/gen_xxx.png） */
    private String downloadToLocal(String ossUrl, String prompt) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(ossUrl).toURL().openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            String filename = "gen_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            Path dir = Paths.get(IMAGE_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path target = dir.resolve(filename);

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return target.toString();
        } catch (Exception e) {
            log.error("下载图片到本地失败: {}", ossUrl, e);
            throw new RuntimeException("下载生成图片失败", e);
        }
    }

    /** 安全关键词检查，返回命中的违规词，null 表示通过 */
    private String checkSafety(String prompt) {
        if (prompt == null) return null;
        for (String keyword : BLOCKED_KEYWORDS) {
            if (prompt.contains(keyword)) {
                log.warn("图片生成被关键词拦截: {}", keyword);
                return keyword;
            }
        }
        return null;
    }

    private String extractTextResult(MultiModalConversationResult result) {
        List<Map<String, Object>> content = result.getOutput()
                .getChoices().get(0)
                .getMessage().getContent();
        for (Map<String, Object> item : content) {
            if (item.containsKey("text")) {
                return (String) item.get("text");
            }
        }
        return "无回复内容";
    }
}
