package com.Myself.demo.bot;

import com.Myself.demo.service.ChatService;
import com.Myself.demo.service.ImageService;
import com.Myself.demo.service.LlmService;
import com.Myself.demo.service.VoiceService;
import com.Myself.demo.service.VoicePreferenceService;
import com.Myself.demo.service.RagService;
import com.Myself.demo.tool.OrderTool;
import com.Myself.demo.util.FileUtil;
import com.Myself.demo.util.TimeUtil;
import com.alibaba.dashscope.utils.OSSUtils;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.Path;

@Slf4j
@Service
public class WeChatBotService {

    @Autowired
    private CommandRouter commandRouter;

    @Autowired
    private ImageService imageService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private VoiceService voiceService;

    @Autowired
    private VoicePreferenceService voicePreferenceService;

    @Autowired
    private BotManager botManager;

    @Autowired
    private WeChatClientRegistry clientRegistry;

    @Autowired
    private LlmService llmService;

    @Autowired
    private OrderTool orderTool;

    @Autowired
    private RagService ragService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.Myself.demo.mapper.AdminMapper adminMapper;

    @Value("${llm.api-key}")
    private String apiKey;

    private ILinkClient client;
    private static final ThreadLocal<ILinkClient> currentClient = new ThreadLocal<>();
    private static final long IMAGE_BUFFER_TTL = 5 * 60 * 1000;

    private static final ThreadLocal<String> currentAccountId = new ThreadLocal<>();

    private ILinkClient getClient() {
        String aid = currentAccountId.get();
        if (aid != null) {
            ILinkClient c = clientRegistry.get(aid);
            if (c != null) return c;
        }
        // fallback: 搜 main 或默认
        var all = clientRegistry.all();
        if (!all.isEmpty()) return all.values().iterator().next();
        return client;
    }
    private static final int MAX_IMAGES = 5;

    private static final String MEAL_PROMPT = "请严格按照以下格式分析这张食物照片：\n"
            + "1. 逐一列出识别到的食物名称和估算分量（例如：白米饭约200克、红烧肉约100克）\n"
            + "2. 估算每种食物的热量（千卡）\n"
            + "3. 给出这餐的总估算热量\n"
            + "4. 简要评价这餐的营养搭配\n"
            + "只输出分析结果，不要额外的寒暄。";

    private static final long MEAL_LOCK_TTL = 2 * 60 * 1000;

    private final ConcurrentHashMap<String, Long> mealAnalysisLocks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, List<ImageEntry>> imageBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastFileResult = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastFileContent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastFileName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> lastFileBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> processedMessages = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(16);

    private static class ImageEntry {
        byte[] bytes;
        String desc;
        long timestamp;

        ImageEntry(byte[] bytes, String desc) {
            this.bytes = bytes;
            this.desc = desc;
            this.timestamp = TimeUtil.nowMillis();
        }
    }
    public void start() {
        botManager.setMessageHandler(this::handleMessages);
        botManager.start("main");
    }

    public void startBot(String name) {
        botManager.start(name);
    }

    private void handleMessages(List<WeixinMessage> messages, String accountId) {
        currentAccountId.set(accountId);
        try {
            for (WeixinMessage msg : messages) {
                if (processedMessages.putIfAbsent(msg.getMessage_id(), Boolean.TRUE) != null) continue;
                final String aid = accountId;
                executor.submit(() -> {
                    currentAccountId.set(aid);
                    try { handleMessage(msg); } finally { currentAccountId.remove(); }
                });
            }
            if (processedMessages.size() > 1000) processedMessages.clear();
        } finally {
            currentAccountId.remove();
        }
    }

    private void handleMessage(WeixinMessage msg) {
        ToolExecutionContext.getAndClear();
        if (msg.getItem_list() == null) return;

        String fromUserId = msg.getFrom_user_id();

        MessageItem voiceItem = extractVoice(msg);
        if (voiceItem != null) {
            handleVoiceMessage(fromUserId, voiceItem);
            return;
        }

        MessageItem fileItem = extractFile(msg);
        if (fileItem != null) {
            handleFileMessage(fromUserId, fileItem, extractText(msg));
            return;
        }

        MessageItem imageItem = extractImage(msg);
        if (imageItem != null) {
            handleImageMessage(fromUserId, imageItem, extractText(msg));
            return;
        }

        String text = extractText(msg);
        if (text == null || text.isEmpty()) return;

        log.info("收到微信消息 from={}, text={}", fromUserId, text);

        // 知识库上传关键词 → 入库最近的文件
        log.info("检查知识库上传关键词: text={}", text);
        if (isUploadCommand(text)) {
            log.info("命中关键词，准备入库");
            byte[] savedBytes = lastFileBytes.get(fromUserId);
            String savedName = lastFileName.get(fromUserId);
            log.info("lastFileBytes={}, lastFileName={}", savedBytes != null ? "存在" : "null", savedName);
            if (savedBytes != null && savedName != null) {
                String name = savedName;
                byte[] bytes = savedBytes;
                executor.submit(() -> {
                    try {
                        ragService.ingestDocument(bytes, name);
                        log.info("文件异步入库完成: {}", name);
                    } catch (Exception e) {
                        log.error("文件异步入库失败: {}", name, e);
                    }
                });
                sendReply(fromUserId, "✅ 已把《" + savedName + "》加入知识库，现在可以问相关问题了");
                return;
            } else {
                sendReply(fromUserId, "请先发送一个文件（PDF/Word/TXT），然后再发【上传知识库】");
                return;
            }
        }

        // 知识库管理命令
        String trimmed = text.trim();
        if (trimmed.equals("/知识库") || trimmed.startsWith("/知识库 ")) {
            handleKbListCommand(fromUserId);
            return;
        }
        if (trimmed.startsWith("/删除知识库 ")) {
            handleKbDeleteCommand(fromUserId, trimmed.substring("/删除知识库 ".length()).trim());
            return;
        }
        if (trimmed.equals("/清空知识库")) {
            handleKbClearCommand(fromUserId);
            return;
        }

        String firstWord = text.trim().split("\\s+")[0].toLowerCase();
        if (commandRouter.hasCommand(firstWord)) {
            clearImageBuffer(fromUserId);
            String result = commandRouter.route(text, fromUserId);
            sendReply(fromUserId, result);
            return;
        }

        List<ImageEntry> images = getValidImages(fromUserId);

        if (!images.isEmpty() && isMealRequest(text)) {
            handleMealImageMessage(fromUserId, images, text);
            return;
        }

        if (!images.isEmpty()) {
            String context = buildImageContext(images, text);
            log.info("图片上下文拼接, from={}, 图片数={}", fromUserId, images.size());
            String result = commandRouter.route(context, fromUserId);
            if (result != null && !result.isEmpty()) {
                chatService.addHistory(fromUserId, text, result);
            }
            sendReply(fromUserId, result, images);
            return;
        }

        if (isMealRequest(text)) {
            sendReply(fromUserId, "请先发送一张食物照片（例如这一餐的午餐照片），我会自动识别食物、估算热量并记录到你的健康档案。");
            return;
        }

        String result = commandRouter.route(text, fromUserId);
        sendReply(fromUserId, result);
    }

    private boolean isMealRequest(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        if (containsAny(t, "药", "打卡", "服药")) return false;
        if (t.length() > 30) {
            if (!containsAny(t, "吃", "餐", "饭")) return false;
        }
        return containsAny(t, "午餐", "晚餐", "早餐", "午饭", "晚饭", "早饭", "吃了", "吃的", "在吃",
                "这餐", "一餐", "这顿饭", "饭菜", "餐食", "我的餐", "记录饮食", "吃的东西", "吃了什么",
                "这顿饭", "卡路里", "热量", "饮食", "分析一下这", "看看我吃的");
    }

    private void handleMealImageMessage(String fromUserId, List<ImageEntry> images, String text) {
        log.info("餐食图片消息触发, from={}, 图片数={}, text={}", fromUserId, images.size(), text);
        ImageEntry latest = images.get(images.size() - 1);
        String foodRecognition = latest.desc;
        try {
            if (foodRecognition == null || foodRecognition.isEmpty()
                    || !containsAny(foodRecognition, "食物", "热量", "千卡", "米饭", "菜", "肉", "碗", "盘")) {
                log.info("缓存描述不含食物信息，重新用餐食识别提示识别: {}", foodRecognition == null ? "null" : foodRecognition.substring(0, Math.min(30, foodRecognition.length())));
                foodRecognition = imageService.recognizeImage(latest.bytes, MEAL_PROMPT);
                latest.desc = foodRecognition;
            }
        } catch (Exception e) {
            log.error("餐食图片重新识别失败, 使用缓存描述", e);
        }
        if (!runMealAnalysis(fromUserId, latest.bytes, text, foodRecognition)) {
            return;
        }
    }

    private boolean tryAcquireMealAnalysis(String userId) {
        long now = System.currentTimeMillis();
        Long prev = mealAnalysisLocks.putIfAbsent(userId, now);
        if (prev == null) return true;
        if (now - prev < MEAL_LOCK_TTL) return false;
        return mealAnalysisLocks.replace(userId, prev, now);
    }

    private boolean runMealAnalysis(String fromUserId, byte[] imageBytes, String userText) {
        return runMealAnalysis(fromUserId, imageBytes, userText, null);
    }

    private boolean runMealAnalysis(String fromUserId, byte[] imageBytes, String userText, String existingRecognition) {
        if (!tryAcquireMealAnalysis(fromUserId)) {
            log.info("餐分析防重锁：该用户近期已触发过餐分析，跳过重复触发, from={}", fromUserId);
            return false;
        }
        String foodRecognition = existingRecognition;
        if (foodRecognition == null || foodRecognition.isEmpty()) {
            try {
                foodRecognition = imageService.recognizeImage(imageBytes, MEAL_PROMPT);
            } catch (Exception e) {
                log.error("餐食图片识别失败", e);
                foodRecognition = "未能识别图片中的食物";
            }
        }
        String userDesc = userText == null || userText.trim().isEmpty() ? "。" : "，用户说：" + userText.trim() + "。";
        String result = commandRouter.route(
                "用户发来了一餐的食物照片" + userDesc
                + "食物识别结果如下：\n\n" + foodRecognition
                + "\n\n请调用 analyze_meal 工具分析这一餐。餐别从用户说的内容推断（早餐/午餐/晚餐/加餐）。\n"
                + "回复要求：请用亲切、生动、可爱的语气，使用 emoji 表情符号（不要使用颜文字/kaomoji）；"
                + "必须先清楚列出这一餐吃到的每样食物（食物名称、估算分量、单项热量），再给出总热量，"
                + "结合用户健康档案与知识库给出具体、详细、可执行的饮食建议。"
                + "不要使用 Markdown 表格/标题符号，用换行和短句排版，让普通微信用户看着舒服。",
                fromUserId);
        clearImageBuffer(fromUserId);
        if (userText != null && !userText.trim().isEmpty() && result != null && !result.isEmpty()) {
            chatService.addHistory(fromUserId, userText, result);
        }
        sendReply(fromUserId, result);
        return true;
    }

    private boolean isFoodDescription(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        if (desc.contains("【食物】")) return true;
        return containsAny(desc, "千卡", "总估算热量", "食物名称");
    }

    private String stripFoodJudgement(String desc) {
        if (desc == null || desc.isEmpty()) return desc;
        String[] lines = desc.split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.contains("不是食物") || t.contains("未出现任何食物") || t.contains("未出现食物")
                    || t.contains("不属于食物") || t.contains("不属于餐食") || t.contains("不包含食物")
                    || t.contains("不是餐食") || t.contains("无食物") || t.contains("没有食物")) {
                continue;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private List<ImageEntry> getValidImages(String userId) {
        List<ImageEntry> images = imageBuffers.get(userId);
        if (images == null || images.isEmpty()) return List.of();

        images.removeIf(e -> TimeUtil.isExpired(e.timestamp, IMAGE_BUFFER_TTL));

        if (images.isEmpty()) {
            imageBuffers.remove(userId);
            log.info("图片缓存已过期, userId={}", userId);
        }
        return images;
    }

    private String buildImageContext(List<ImageEntry> images, String text) {
        StringBuilder sb = new StringBuilder();
        if (images.size() == 1) {
            sb.append("用户发送了一张图片，图片内容如下：");
            sb.append(images.get(0).desc);
        } else {
            sb.append("用户发送了").append(images.size()).append("张图片：\n");
            for (int i = 0; i < images.size(); i++) {
                sb.append("图片").append(i + 1).append("：").append(images.get(i).desc).append("\n");
            }
        }
        sb.append("\n\n用户现在说：").append(text);
        return sb.toString();
    }

    private void clearImageBuffer(String userId) {
        List<ImageEntry> removed = imageBuffers.remove(userId);
        if (removed != null && !removed.isEmpty()) {
            log.info("清除图片缓存, userId={}, 图片数={}", userId, removed.size());
        }
    }

    private void handleImageMessage(String fromUserId, MessageItem imageItem, String text) {
        log.info("收到图片消息 from={}, text={}", fromUserId, text);

        try {
            byte[] imageBytes = getClient().downloadImageFromMessageItem(imageItem);
            saveUserImage(fromUserId, imageBytes, text);

            String combinedText = text != null ? text.trim() : "";
            if (!combinedText.isEmpty() && isMealRequest(combinedText)) {
                runMealAnalysis(fromUserId, imageBytes, combinedText);
                return;
            }

            if (!combinedText.isEmpty()) {
                String answer = imageService.recognizeImage(imageBytes, combinedText);
                addImageToBuffer(fromUserId, imageBytes, answer);
                chatService.addHistory(fromUserId, "[图片] " + combinedText, answer);
                sendReply(fromUserId, answer);
            } else {
                addImageToBuffer(fromUserId, imageBytes, "");
                String fullDesc = imageService.recognizeImage(imageBytes,
                        "请判断这张图片是否为食物/餐食照片。\n"
                        + "如果是食物照片：请以【食物】开头，然后严格按照以下格式输出：\n"
                        + "1. 逐一列出识别到的食物名称和估算分量（例如：白米饭约200克、红烧肉约100克）\n"
                        + "2. 估算每种食物的热量（千卡）\n"
                        + "3. 给出这餐的总估算热量\n"
                        + "4. 简要评价这餐的营养搭配\n"
                        + "如果不是食物照片：请直接详细描述图片内容（包括可见的文字、数字、符号等）。注意：只需描述图片本身，不要输出“不是食物”“未出现食物”“不属于餐食照片”之类的判断性说明。");
                List<ImageEntry> buffered = imageBuffers.get(fromUserId);
                if (buffered != null && !buffered.isEmpty()) {
                    ImageEntry last = buffered.get(buffered.size() - 1);
                    last.desc = fullDesc;
                }
                if (isFoodDescription(fullDesc)) {
                    log.info("图片识别为食物照片，直接触发餐分析: from={}", fromUserId);
                    runMealAnalysis(fromUserId, imageBytes, "");
                    return;
                }
                chatService.addHistory(fromUserId, "[用户发送了图片]", fullDesc);

                List<ImageEntry> images = imageBuffers.get(fromUserId);
                int count = images != null ? images.size() : 1;
                String cleanedDesc = stripFoodJudgement(fullDesc);
                String msg = "📷 已识别这张图片：\n\n" + cleanedDesc;
                if (count < MAX_IMAGES) {
                    msg += "\n\n需要我帮你做什么？也可以继续发图片（最多" + MAX_IMAGES + "张）或发送文字提问";
                } else {
                    msg += "\n\n已达到最多" + MAX_IMAGES + "张图片，请发送文字提问或发送新图片替换";
                }
                sendReply(fromUserId, msg);
            }
        } catch (Exception e) {
            log.error("处理图片消息失败", e);
            try {
                getClient().sendText(fromUserId, "抱歉，图片接收失败，请稍后再试");
            } catch (Exception ex) {
                log.error("发送错误回复失败", ex);
            }
        }
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    private void saveUserImage(String userId, byte[] bytes, String desc) {
        try {
            String filename = "user_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            java.nio.file.Path dir = java.nio.file.Paths.get("src/main/resources/static/images");
            if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(filename);
            java.nio.file.Files.write(target, bytes);

            String url = "/images/" + filename;
            try {
                com.Myself.demo.entity.UserImage img = new com.Myself.demo.entity.UserImage();
                img.setFromUserId(userId);
                img.setDiskPath(url);
                img.setRecognizedDesc(desc);
                adminMapper.insertImage(img);
                com.Myself.demo.entity.UserMessage msg = new com.Myself.demo.entity.UserMessage();
                msg.setFromUserId(userId);
                msg.setRole("user");
                msg.setMsgType("image");
                msg.setDirection("user");
                msg.setMsgId(img.getId());
                msg.setContent(url);
                adminMapper.insertMsg(msg);
            } catch (Exception e) {
                log.warn("图片落库失败(不影响发送): {}", e.getMessage());
            }
            log.info("用户图片已保存: userId={}, url={}", userId, url);
        } catch (Exception e) {
            log.error("保存用户图片失败", e);
        }
    }

    private void saveUserFile(String userId, byte[] bytes, String fileName) {
        try {
            String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            String filename = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
            java.nio.file.Path dir = java.nio.file.Paths.get("src/main/resources/static/files");
            if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(filename);
            java.nio.file.Files.write(target, bytes);

            String url = "/files/" + filename;
            try {
                com.Myself.demo.entity.UserFile f = new com.Myself.demo.entity.UserFile();
                f.setFromUserId(userId);
                f.setFileName(fileName);
                f.setDiskPath(url);
                f.setContentText("");
                adminMapper.insertFile(f);
                com.Myself.demo.entity.UserMessage msg = new com.Myself.demo.entity.UserMessage();
                msg.setFromUserId(userId);
                msg.setRole("user");
                msg.setMsgType("file");
                msg.setDirection("user");
                msg.setMsgId(f.getId());
                msg.setContent(fileName);
                adminMapper.insertMsg(msg);
            } catch (Exception e) {
                log.warn("文件落库失败(不影响发送): {}", e.getMessage());
            }
            log.info("用户文件已保存: userId={}, url={}", userId, url);
        } catch (Exception e) {
            log.error("保存用户文件失败", e);
        }
    }

    private void addImageToBuffer(String userId, byte[] bytes, String desc) {
        List<ImageEntry> images = imageBuffers.computeIfAbsent(userId, k -> new ArrayList<>());
        if (images.size() >= MAX_IMAGES) {
            images.remove(0);
        }
        images.add(new ImageEntry(bytes, desc));
        log.info("添加图片到缓存, userId={}, 当前图片数={}", userId, images.size());
    }

    private void sendReply(String fromUserId, String result) {
        sendReply(fromUserId, result, null);
    }

    private void sendReply(String fromUserId, String result, List<ImageEntry> images) {
        ToolExecutionContext.PendingAction action = ToolExecutionContext.getAndClear();

        if (result == null || result.isEmpty()) {
            try {
                getClient().sendText(fromUserId, "抱歉，我没理解你的意思，可以再说详细一点吗？");
            } catch (Exception e) {
                log.error("发送澄清回复失败", e);
            }
            return;
        }

        try {
            if (action != null) {
                switch (action.type()) {
                    case "image_gen":
                    case "image_transform":
                        // 不直接发送 LLM 生成的文本(可能包含幻觉的假图片链接), 由 doImageGen/doImageTransform 统一处理
                        if ("image_gen".equals(action.type())) {
                            doImageGen(fromUserId, action.prompt());
                        } else {
                            doImageTransform(fromUserId, action.prompt(), images);
                        }
                        return;
                    case "file_translate":
                    case "file_extract":
                    case "file_search":
                        doFileToolAction(fromUserId, action);
                        return;
                    case "file_export":
                        getClient().sendText(fromUserId, result);
                        doFileExport(fromUserId);
                        return;
                    case "re_examine":
                        doReExamine(fromUserId, action.prompt(), images);
                        return;
                    case "save_file":
                        doSaveAsFile(fromUserId, result, action.arg1());
                        return;
                    case "qr_code":
                        getClient().sendText(fromUserId, result);
                        doQrCode(fromUserId, action.prompt());
                        return;
                    case "decode_qr":
                        doDecodeQr(fromUserId, images);
                        return;
                }
            }

            getClient().sendText(fromUserId, result);
            log.info("回复消息成功: {}", result.substring(0, Math.min(50, result.length())));

            if (action == null && voicePreferenceService.isVoiceEnabled(fromUserId)) {
                try {
                    String voiceCode = voicePreferenceService.getVoiceCode(fromUserId);
                    byte[] mp3Bytes = voiceService.textToSpeechMp3(result, voiceCode);
                    if (mp3Bytes != null) {
                        getClient().sendFile(fromUserId, mp3Bytes, "语音.mp3", "");
                        log.info("语音文件发送成功(MP3), size={}bytes", mp3Bytes.length);
                    }
                } catch (Exception e) {
                    log.warn("语音发送失败", e);
                } finally {
                    voicePreferenceService.disableVoice(fromUserId);
                }
            }
        } catch (Exception e) {
            log.error("回复失败", e);
            try {
                getClient().sendText(fromUserId, "抱歉，操作失败，请稍后再试");
            } catch (Exception ex) {
                log.error("发送错误回复失败", ex);
            }
        }
    }

    private void doImageGen(String fromUserId, String prompt) {
        log.info("文生图: {}", prompt);
        try {
            getClient().sendText(fromUserId, "正在生成中，请稍候...");
            byte[] imageBytes = imageService.generateImage(prompt);
            getClient().sendImage(fromUserId, imageBytes, "image.png", "");
            log.info("图片发送成功");
        } catch (Exception e) {
            log.error("图片生成失败", e);
            try { getClient().sendText(fromUserId, "抱歉，图片生成失败，请稍后再试"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private void doImageTransform(String fromUserId, String prompt, List<ImageEntry> images) {
        log.info("图生图: {}", prompt);
        try {
            getClient().sendText(fromUserId, "正在处理图片，请稍候...");
            ImageEntry latest = (images == null || images.isEmpty())
                    ? null : images.get(images.size() - 1);
            if (latest == null) {
                getClient().sendText(fromUserId, "请先发送一张要修改的图片，再告诉我怎么改");
                log.info("图生图未找到原图: {}", fromUserId);
                return;
            }
            String localPath = imageService.editImageAndSave(latest.bytes, prompt);
            if (localPath != null && localPath.startsWith("SAFETY_BLOCKED:")) {
                getClient().sendText(fromUserId, "抱歉，图片处理被安全策略拦截");
                return;
            }
            byte[] imageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(localPath));
            getClient().sendImage(fromUserId, imageBytes, "image.png", "");
            log.info("图片发送成功");
        } catch (Exception e) {
            log.error("图片变换失败", e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            try {
                if (msg.contains("SAFETY_BLOCKED")) {
                    getClient().sendText(fromUserId, "抱歉，图片处理被安全策略拦截");
                } else {
                    getClient().sendText(fromUserId, "抱歉，图片处理失败，请稍后再试");
                }
            } catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private void doFileExport(String fromUserId) {
        String cached = lastFileResult.get(fromUserId);
        if (cached == null || cached.isEmpty()) return;
        try {
            getClient().sendFile(fromUserId, cached.getBytes("UTF-8"), "文件总结.txt", "");
            log.info("文件总结已发送: {}", fromUserId);
        } catch (Exception e) {
            log.error("文件总结发送失败", e);
        }
    }

    private void doSaveAsFile(String fromUserId, String content, String fileName) {
        if (content == null || content.isEmpty()) return;
        String name = (fileName != null && !fileName.isEmpty()) ? fileName + ".txt" : "export.txt";
        try {
            getClient().sendFile(fromUserId, content.getBytes("UTF-8"), name, "");
            log.info("文本文件已发送: {}", name);
        } catch (Exception e) {
            log.error("文本文件发送失败", e);
        }
    }

    private void doQrCode(String fromUserId, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            if (text.startsWith("sms:") || text.startsWith("mailto:")) {
                int qIdx = text.indexOf('?');
                if (qIdx > 0) {
                    text = text.substring(0, qIdx + 1)
                            + java.net.URLEncoder.encode(text.substring(qIdx + 1), "UTF-8")
                                    .replace("%3D", "=").replace("%26", "&");
                }
            }
            var writer = new com.google.zxing.qrcode.QRCodeWriter();
            var matrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 300, 300);
            var image = new java.awt.image.BufferedImage(300, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 300; x++) {
                for (int y = 0; y < 300; y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            var baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            getClient().sendImage(fromUserId, baos.toByteArray(), "qrcode.png", "");
            log.info("二维码已发送: {}", text);
        } catch (Exception e) {
            log.error("二维码生成失败", e);
            try { getClient().sendText(fromUserId, "二维码生成失败"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private void doDecodeQr(String fromUserId, List<ImageEntry> images) {
        if (images == null || images.isEmpty()) {
            try { getClient().sendText(fromUserId, "请先发送一张包含二维码或条形码的图片"); }
            catch (Exception e) { log.error("发送错误回复失败", e); }
            return;
        }
        try {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                    images.get(images.size() - 1).bytes));
            if (image == null) {
                try { getClient().sendText(fromUserId, "图片无法读取"); }
                catch (Exception e) { log.error("发送错误回复失败", e); }
                return;
            }
            var bitmap = new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image);
            var binaryBitmap = new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(bitmap));
            var reader = new com.google.zxing.MultiFormatReader();
            var result = reader.decode(binaryBitmap);
            String typeName = result.getBarcodeFormat().name();
            getClient().sendText(fromUserId, "识别结果（" + typeName + "）：" + result.getText());
            log.info("条码识别成功: {} ({})", result.getText(), typeName);
        } catch (com.google.zxing.NotFoundException e) {
            try { getClient().sendText(fromUserId, "图片中未检测到二维码或条形码"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        } catch (Exception e) {
            log.error("条码识别失败", e);
            try { getClient().sendText(fromUserId, "识别失败"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private void doReExamine(String fromUserId, String question, List<ImageEntry> images) {
        if (images == null || images.isEmpty()) return;
        ImageEntry latest = images.get(images.size() - 1);
        String prompt = "请仔细观察图片";
        if (question != null && !question.isEmpty()) {
            prompt += "，只回答这个问题：" + question;
        }
        prompt += "。直接回答，不要输出无关的图片描述。";
        try {
            getClient().sendText(fromUserId, "正在重新识别中...");
            String answer = imageService.recognizeImage(latest.bytes, prompt);
            latest.desc = answer;
            getClient().sendText(fromUserId, answer);
            log.info("重新识别图片成功");
        } catch (Exception e) {
            log.error("重新识别图片失败", e);
            try { getClient().sendText(fromUserId, "抱歉，重新识别失败，请稍后再试"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private void doFileToolAction(String fromUserId, ToolExecutionContext.PendingAction action) {
        String content = lastFileContent.get(fromUserId);
        String fileName = lastFileName.get(fromUserId);
        if (content == null || content.isEmpty()) {
            try { getClient().sendText(fromUserId, "没有找到已上传的文件内容，请先发送文件"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
            return;
        }

        String baseName = fileName != null ? fileName.replaceAll("\\.[^.]+$", "") : "file";
        String output = null;
        String outputName = null;

        switch (action.type()) {
            case "file_translate": {
                String lang = action.arg1() != null ? action.arg1() : "英语";
                String instruction = action.arg2() != null ? action.arg2() : "";
                String prompt = "请将以下文件内容翻译为" + lang + (instruction.isEmpty() ? "" : "，" + instruction) + "：\n\n" + content;
                output = llmService.chat(fromUserId, java.util.List.of(java.util.Map.of("role", "user", "content", prompt)));
                outputName = baseName + "_翻译_" + lang + ".txt";
                break;
            }
            case "file_extract": {
                String keyword = action.arg1() != null ? action.arg1() : "";
                String format = action.arg2() != null ? action.arg2() : "原文";
                String prompt = "请从以下文件内容中提取" + keyword + "，以" + format + "格式输出：\n\n" + content;
                output = llmService.chat(fromUserId, java.util.List.of(java.util.Map.of("role", "user", "content", prompt)));
                outputName = baseName + "_提取_" + keyword + ".txt";
                break;
            }
            case "file_search": {
                String query = action.arg1() != null ? action.arg1() : "";
                int contextLines = action.argInt() > 0 ? action.argInt() : 2;
                String prompt = "请在以下文件内容中搜索\"" + query + "\"，显示匹配行及前后" + contextLines + "行上下文：\n\n" + content;
                output = llmService.chat(fromUserId, java.util.List.of(java.util.Map.of("role", "user", "content", prompt)));
                outputName = baseName + "_搜索_" + query + ".txt";
                break;
            }
        }

        if (output != null && !output.isEmpty()) {
            try {
                getClient().sendFile(fromUserId, output.getBytes("UTF-8"), outputName, "");
                log.info("文件工具结果已发送: {}", outputName);
            } catch (Exception e) {
                log.error("发送文件失败", e);
                try { getClient().sendText(fromUserId, "文件处理完成但发送失败"); }
                catch (Exception ex) { log.error("发送错误回复失败", ex); }
            }
        } else {
            try { getClient().sendText(fromUserId, "处理失败，请稍后重试"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private void handleFileMessage(String fromUserId, MessageItem fileItem, String text) {
        String fileName = fileItem.getFile_item() != null
                ? fileItem.getFile_item().getFile_name() : "file";
        log.info("收到文件消息 from={}, file={}", fromUserId, fileName);
        try {
            byte[] fileBytes = getClient().downloadFileFromMessageItem(fileItem);
            saveUserFile(fromUserId, fileBytes, fileName);
            String content = extractTextContent(fileBytes, fileName);

            if (content == null || content.isEmpty()) {
                Path tmp = FileUtil.createTempFile("wxfile_", "_" + fileName, fileBytes);
                String ossUrl = OSSUtils.upload("qwen-plus", tmp.toAbsolutePath().toString(), apiKey);
                FileUtil.deleteTempFile(tmp);
                content = "文件链接: " + ossUrl;
            }

        boolean isKnowledgeUpload = isUploadCommand(text);
        if (isKnowledgeUpload) {
            try {
                ragService.ingestDocument(fileBytes, fileName);
                log.info("文件已加入知识库: {}", fileName);
            } catch (Exception e) {
                log.error("知识库入库失败", e);
            }
        }

        lastFileBytes.put(fromUserId, fileBytes);
        lastFileContent.put(fromUserId, content);
        lastFileName.put(fromUserId, fileName);

        String question;
        if (isKnowledgeUpload) {
            question = "用户上传了一个文件(" + fileName + ")，内容已加入知识库。作为知识库管理员，请详细概括这个文件的主要内容，方便用户了解已上传了什么知识。文件内容如下：\n\n" + content;
        } else if (text != null && !text.isEmpty()) {
            question = "用户上传了一个文件(" + fileName + ")，文件内容如下：\n\n" + content
                    + "\n\n用户说：" + text;
        } else {
            question = "用户上传了一个文件(" + fileName + ")，文件内容如下：\n\n" + content
                    + "\n\n请概括这个文件的内容";
        }

            String result = commandRouter.route(question, fromUserId);
            sendReply(fromUserId, result);
            lastFileResult.put(fromUserId, result);
        } catch (Exception e) {
            log.error("处理文件消息失败", e);
            try { getClient().sendText(fromUserId, "抱歉，文件处理失败，请稍后再试"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private void handleFileToolResult(String fromUserId, String result) {
        try {
            String content = lastFileContent.get(fromUserId);
            String fileName = lastFileName.get(fromUserId);
            if (content == null || content.isEmpty()) {
                getClient().sendText(fromUserId, "没有找到已上传的文件内容，请先发送文件");
                return;
            }

            String baseName = fileName != null ? fileName.replaceAll("\\.[^.]+$", "") : "file";
            String jsonArgs = result.substring(result.indexOf(':') + 1);
            com.fasterxml.jackson.databind.JsonNode args = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonArgs);

            String output = null;
            String outputName = null;

            if (result.startsWith("FILE_TRANSLATE:")) {
                String lang = args.has("target_language") ? args.get("target_language").asText() : "英语";
                String instruction = args.has("instruction") ? args.get("instruction").asText() : "";
                String prompt = "请将以下文件内容翻译为" + lang + (instruction.isEmpty() ? "" : "，" + instruction) + "：\n\n" + content;
                output = llmService.chat(fromUserId, List.of(Map.of("role", "user", "content", prompt)));
                outputName = baseName + "_翻译_" + lang + ".txt";
            } else if (result.startsWith("FILE_EXTRACT:")) {
                String keyword = args.has("keyword") ? args.get("keyword").asText() : "";
                String format = args.has("format") ? args.get("format").asText() : "原文";
                String prompt = "请从以下文件内容中提取" + keyword + "，以" + format + "格式输出：\n\n" + content;
                output = llmService.chat(fromUserId, List.of(Map.of("role", "user", "content", prompt)));
                outputName = baseName + "_提取_" + keyword + ".txt";
            } else if (result.startsWith("FILE_SEARCH:")) {
                String query = args.has("query") ? args.get("query").asText() : "";
                int contextLines = args.has("context_lines") ? args.get("context_lines").asInt() : 2;
                String prompt = "请在以下文件内容中搜索\"" + query + "\"，显示匹配行及前后" + contextLines + "行上下文：\n\n" + content;
                output = llmService.chat(fromUserId, List.of(Map.of("role", "user", "content", prompt)));
                outputName = baseName + "_搜索_" + query + ".txt";
            }

            if (output != null && !output.isEmpty()) {
                getClient().sendFile(fromUserId, output.getBytes("UTF-8"), outputName, "");
                log.info("文件工具结果已发送: {}", outputName);
            } else {
                getClient().sendText(fromUserId, "处理失败，请稍后重试");
            }
        } catch (Exception e) {
            log.error("处理文件工具结果失败", e);
            try { getClient().sendText(fromUserId, "文件处理失败，请稍后重试"); }
            catch (Exception ex) { log.error("发送错误回复失败", ex); }
        }
    }

    private String extractTextContent(byte[] bytes, String fileName) {
        String lower = fileName.toLowerCase();

        boolean isOffice = FileUtil.isOfficeExtension(lower);
        if (!isOffice && FileUtil.isBinarySignature(bytes)) return null;

        boolean isText = FileUtil.isTextExtension(lower);

        if (isText) return FileUtil.decodeText(bytes, 50000);
        if (isOffice) return extractWithPoi(bytes, fileName);

        return null;
    }

    private String extractWithPoi(byte[] bytes, String fileName) {
        try {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".pdf")) {
                var doc = Loader.loadPDF(bytes);
                var stripper = new PDFTextStripper();
                String text = stripper.getText(doc);
                doc.close();
                return text.length() > 50000 ? text.substring(0, 50000) + "\n...(内容过长已截断)" : text;
            }
            if (lower.endsWith(".docx")) {
                XWPFDocument doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes));
                XWPFWordExtractor ex = new XWPFWordExtractor(doc);
                String text = ex.getText();
                ex.close();
                return text.length() > 50000 ? text.substring(0, 50000) + "\n...(内容过长已截断)" : text;
            }
            if (lower.endsWith(".xlsx")) {
                XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    sb.append("Sheet: ").append(wb.getSheetName(i)).append("\n");
                    var sheet = wb.getSheetAt(i);
                    for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                        var row = sheet.getRow(r);
                        if (row != null) {
                            for (var cell : row) {
                                String v = cell.toString().trim();
                                if (!v.isEmpty()) sb.append(v).append("\t");
                            }
                            sb.append("\n");
                        }
                    }
                }
                wb.close();
                String text = sb.toString();
                return text.length() > 50000 ? text.substring(0, 50000) + "\n...(内容过长已截断)" : text;
            }
        } catch (Exception e) {
            log.warn("POI 提取失败, 回退文本尝试: {}", e.getMessage());
        }
        return FileUtil.decodeText(bytes, 5000);
    }

    private MessageItem extractFile(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) return item;
        }
        return null;
    }

    private void handleVoiceMessage(String fromUserId, MessageItem voiceItem) {
        log.info("收到语音消息 from={}", fromUserId);
        String transcript = voiceItem.getVoice_item().getText();
        if (transcript == null || transcript.isEmpty()) {
            transcript = "[语音消息，未能识别文字]";
        }
        log.info("语音转文字: {}", transcript);

        String result = commandRouter.route(transcript, fromUserId);
        if (result != null && !result.isEmpty()) {
            chatService.addHistory(fromUserId, transcript, result);
        }
        sendReply(fromUserId, result);
    }

    private MessageItem extractVoice(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null) {
                return item;
            }
        }
        return null;
    }

    private MessageItem extractImage(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                return item;
            }
        }
        return null;
    }

    private String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) return null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    private String extractFirstSentence(String text) {
        if (text == null || text.isEmpty()) return "";
        int period = text.indexOf("。");
        if (period > 0 && period < 200) {
            return text.substring(0, period + 1);
        }
        int newline = text.indexOf("\n");
        if (newline > 0 && newline < 200) {
            return text.substring(0, newline);
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    public boolean sendReminder(String userId, String content) {
        if (client == null) return false;
        try {
            getClient().sendText(userId, "⏰ 提醒：" + content);
            log.info("定时提醒已发送: userId={}, content={}", userId, content);
            return true;
        } catch (Exception e) {
            log.error("定时提醒发送失败", e);
            return false;
        }
    }

    private void handleKbListCommand(String fromUserId) {
        try {
            List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT doc_name, COUNT(*) AS chunks, SUM(LENGTH(chunk_text)) AS chars FROM doc_embeddings GROUP BY doc_name ORDER BY doc_name");
            if (docs.isEmpty()) {
                sendReply(fromUserId, "知识库为空，请先发送文件并说「上传知识库」");
                return;
            }
            StringBuilder sb = new StringBuilder("📚 知识库列表\n");
            for (Map<String, Object> row : docs) {
                sb.append("· ").append(row.get("doc_name"))
                  .append(" (").append(row.get("chunks")).append("块, ")
                  .append(row.get("chars")).append("字)\n");
            }
            sendReply(fromUserId, sb.toString().trim());
        } catch (Exception e) {
            log.error("查询知识库失败", e);
            sendReply(fromUserId, "查询失败");
        }
    }

    private void handleKbDeleteCommand(String fromUserId, String name) {
        try {
            int deleted = jdbcTemplate.update("DELETE FROM doc_embeddings WHERE doc_name LIKE ?", "%" + name + "%");
            if (deleted > 0) {
                sendReply(fromUserId, "已删除包含「" + name + "」的 " + (deleted / 1) + " 条记录");
            } else {
                sendReply(fromUserId, "未找到匹配的知识库内容");
            }
        } catch (Exception e) {
            log.error("删除知识库失败", e);
            sendReply(fromUserId, "删除失败");
        }
    }

    private void handleKbClearCommand(String fromUserId) {
        try {
            jdbcTemplate.update("DELETE FROM doc_embeddings");
            sendReply(fromUserId, "知识库已清空");
        } catch (Exception e) {
            log.error("清空知识库失败", e);
            sendReply(fromUserId, "清空失败");
        }
    }

    private boolean isUploadCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.contains("上传攻略") || t.contains("上传知识库") || t.contains("加入知识库") || t.contains("/入库");
    }

    @PreDestroy
    public void cleanup() {
        if (client != null) {
            getClient().close();
            log.info("iLink 客户端已关闭");
        }
        executor.shutdownNow();
        log.info("消息线程池已关闭");
    }
}
