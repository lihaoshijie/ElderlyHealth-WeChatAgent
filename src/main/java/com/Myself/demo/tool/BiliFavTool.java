package com.Myself.demo.tool;

import com.Myself.demo.bot.ToolExecutionContext;
import com.example.demo.BiliQrLogin;
import com.example.demo.model.BiliVideo;
import com.example.demo.service.FavService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * B站收藏夹工具。
 *
 * <p>登录态只保存在当前进程的、按微信用户隔离的会话中，绝不读取、写入或回退到
 * 配置文件中的共享 Cookie。应用重启、登录失效或用户主动重新登录后，均须重新扫码。</p>
 */
@Slf4j
@Component
public class BiliFavTool {

    private final Map<String, BiliSession> sessions = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "bili_login_qr", value = "为当前用户发起B站官方二维码登录。用户没有登录态、要求登录或二维码过期时调用；二维码会直接发送给当前用户。")
    public String loginQr(@P("系统用户ID，不需要向用户询问") String userId) {
        return startLogin(userId, true);
    }

    @Tool(name = "bili_login_confirm", value = "确认当前用户已经用B站App扫码并在手机上确认登录。仅在用户回复“已扫码”或“已确认”时调用。")
    public String loginConfirm(@P("系统用户ID，不需要向用户询问") String userId) {
        BiliSession session = sessions.computeIfAbsent(userKey(userId), ignored -> new BiliSession());
        synchronized (session) {
            if (session.pendingLogin == null || session.pendingQr == null) {
                return "BILI_LOGIN_NOT_PENDING：没有待确认的B站二维码。请先调用 bili_login_qr。";
            }
            try {
                String cookie = session.pendingLogin.login(60, session.pendingQr);
                if (cookie == null || cookie.isBlank()) {
                    clearPending(session);
                    return "BILI_LOGIN_FAILED：未取得登录凭证，请重新扫码。";
                }
                closeFav(session);
                session.cookie = cookie;
                session.favService = new FavService(cookie);
                clearPending(session);
                return "BILI_LOGIN_SUCCEEDED：当前用户已完成B站扫码登录。现在可以继续查询收藏夹或读取视频字幕。";
            } catch (Exception e) {
                clearPending(session);
                String reason = safeMessage(e);
                if (isExpired(reason)) {
                    return "BILI_QR_EXPIRED：二维码已过期，请调用 bili_login_qr 重新生成二维码。";
                }
                log.warn("B站扫码登录失败, userId={}, reason={}", userKey(userId), reason);
                return "BILI_LOGIN_FAILED：扫码登录未完成（" + reason + "）。请重新生成二维码后再试。";
            }
        }
    }

    @Tool(name = "bili_fav_folders", value = "查询当前用户的B站收藏夹列表。会先检查当前用户的登录态；未登录时会自动生成并发送B站官方二维码，必须等待用户扫码确认。")
    public String listFolders(@P("系统用户ID，不需要向用户询问") String userId) {
        FavService fav = loggedInFavOrRequestLogin(userId);
        if (fav == null) return loginRequiredMessage(userId);
        try {
            Map<Long, String> folders = fav.listFavFolders();
            if (folders == null || folders.isEmpty()) {
                return "BILI_EMPTY_FOLDERS：该用户当前没有可访问的收藏夹。请明确告知收藏夹为空，并可建议用户在B站先收藏视频后重试。";
            }
            StringBuilder result = new StringBuilder("BILI_FOLDERS：\n");
            folders.forEach((id, name) -> result.append("- ").append(name).append(" (ID:").append(id).append(")\n"));
            return result.toString();
        } catch (Exception e) {
            return handleFavFailure(userId, "查询收藏夹", e);
        }
    }

    @Tool(name = "bili_fav_videos", value = "读取指定B站收藏夹的视频及字幕缓存。必须提供 bili_fav_folders 返回的收藏夹ID；随后可用 bili_video_subtitle 回答某个视频内容问题。")
    public String fetchVideos(@P("收藏夹ID编号") String favId,
                              @P("系统用户ID，不需要向用户询问") String userId) {
        FavService fav = loggedInFavOrRequestLogin(userId);
        if (fav == null) return loginRequiredMessage(userId);
        try {
            long id = Long.parseLong(favId);
            // 该调用会拉取视频及其字幕，之后才可针对 BV 号检索并总结内容。
            int count = fav.fetchFavWithSubtitle(id);
            List<BiliVideo> videos = fav.listAllVideos();
            if (videos == null || videos.isEmpty()) {
                return "BILI_EMPTY_VIDEOS：该收藏夹没有可访问的视频。请告知用户收藏夹为空或视频已失效，不要编造视频内容。";
            }

            StringBuilder result = new StringBuilder("BILI_FAV_VIDEOS：共 ").append(count).append(" 个\n");
            int limit = Math.min(20, videos.size());
            for (int i = 0; i < limit; i++) {
                BiliVideo video = videos.get(i);
                // listAllVideos() 底层只返回元数据，subtitleText 字段始终为空；
                // 必须按 BV 号从字幕缓存读取，否则会把已抓到的字幕显示为“无”。
                String subtitleText = fav.getSubtitleText(video.getBvId());
                String subtitleStatus = subtitleText == null || subtitleText.isBlank()
                        ? "未获取"
                        : "已获取（" + subtitleText.length() + "字）";
                result.append("- ").append(video.getTitle())
                        .append(" [").append(video.getBvId()).append("]")
                        .append(" UP:").append(video.getUpName())
                        .append(" ").append(formatDuration(video.getDuration()))
                        .append(" 字幕:").append(subtitleStatus)
                        .append("\n");
            }
            if (videos.size() > limit) result.append("（仅展示前20个视频）");
            return result.toString();
        } catch (NumberFormatException e) {
            return "BILI_INVALID_FOLDER_ID：收藏夹ID必须是 bili_fav_folders 返回的数字ID。";
        } catch (Exception e) {
            return handleFavFailure(userId, "读取收藏夹视频", e);
        }
    }

    @Tool(name = "bili_video_subtitle", value = "取得已读取收藏夹中的指定BV号视频字幕，用于根据字幕总结、问答或提取要点。没有字幕时必须如实说明，不能根据标题猜测内容。")
    public String subtitle(@P("视频BV号") String bvId,
                           @P("系统用户ID，不需要向用户询问") String userId) {
        FavService fav = loggedInFavOrRequestLogin(userId);
        if (fav == null) return loginRequiredMessage(userId);
        BiliSession session = sessions.get(userKey(userId));

        // FavService 只会从其本地 SQLite 缓存读取字幕。缓存为空不代表视频没有字幕，
        // 因此每次查询具体 BV 号时先访问 B 站播放页和字幕接口。
        Exception directFetchError = null;
        try {
            String text = fetchSubtitleFromBili(bvId, session != null ? session.cookie : null);
            if (text != null && !text.isBlank()) {
                return "BILI_SUBTITLE [" + bvId + "]：\n" + truncateSubtitle(text);
            }
        } catch (Exception e) {
            directFetchError = e;
            log.warn("直接获取B站字幕失败, bvId={}, reason={}", bvId, safeMessage(e));
        }

        try {
            // 兼容已由 fetchFavWithSubtitle 写入的旧缓存。
            String text = fav.getSubtitleText(bvId);
            if (text != null && !text.isBlank()) {
                return "BILI_SUBTITLE [" + bvId + "]：\n" + truncateSubtitle(text);
            }
            if (directFetchError != null) {
                return "BILI_SUBTITLE_FETCH_FAILED：B站字幕接口请求失败（" + safeMessage(directFetchError)
                        + "）。请稍后重试；这不表示视频没有字幕。";
            }
            if (text == null || text.isBlank()) {
                return "BILI_SUBTITLE_UNAVAILABLE：B站未返回该视频可用字幕。视频可能只有自动字幕、分P字幕或当前地区不可用；请向用户说明未能取得字幕，不能虚构总结。";
            }
        } catch (Exception e) {
            return handleFavFailure(userId, "读取视频字幕", e);
        }
        return "BILI_SUBTITLE_UNAVAILABLE：未找到该视频可用字幕。";
    }

    /** 直接调用 B站公开播放与字幕接口，避免把第三方库的缓存未命中误判为“无字幕”。 */
    private String fetchSubtitleFromBili(String bvId, String cookie) throws Exception {
        if (bvId == null || !bvId.matches("(?i)^BV[0-9A-Z]+$")) {
            throw new IllegalArgumentException("BV号格式不正确");
        }
        String encodedBv = URLEncoder.encode(bvId, StandardCharsets.UTF_8);
        JsonNode view = getBiliJson("https://api.bilibili.com/x/web-interface/view?bvid=" + encodedBv, cookie);
        JsonNode data = requireBiliData(view, "读取视频信息");
        long aid = data.path("aid").asLong();
        long cid = data.path("cid").asLong();
        if (cid <= 0 && data.path("pages").isArray() && !data.path("pages").isEmpty()) {
            cid = data.path("pages").get(0).path("cid").asLong();
        }
        if (aid <= 0 || cid <= 0) {
            throw new IllegalStateException("B站没有返回有效的视频分P信息");
        }

        JsonNode player = getBiliJson("https://api.bilibili.com/x/player/v2?aid=" + aid + "&cid=" + cid, cookie);
        JsonNode subtitles = requireBiliData(player, "读取字幕列表").path("subtitle").path("subtitles");
        if (!subtitles.isArray() || subtitles.isEmpty()) return null;

        JsonNode selected = null;
        for (JsonNode candidate : subtitles) {
            String candidateUrl = candidate.path("subtitle_url").asText();
            if (!isUsableSubtitleUrl(candidateUrl)) continue;
            String language = candidate.path("lan").asText("").toLowerCase();
            String languageName = candidate.path("lan_doc").asText("");
            if (language.startsWith("zh") || languageName.contains("中文")) {
                selected = candidate;
                break;
            }
            if (selected == null) selected = candidate;
        }
        if (selected == null) return null;
        String subtitleUrl = selected.path("subtitle_url").asText();
        if (subtitleUrl.startsWith("//")) subtitleUrl = "https:" + subtitleUrl;
        JsonNode body = getBiliJson(subtitleUrl, cookie).path("body");
        if (!body.isArray() || body.isEmpty()) return null;

        StringBuilder text = new StringBuilder();
        for (JsonNode line : body) {
            String content = line.path("content").asText().trim();
            if (!content.isEmpty()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(content);
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private boolean isUsableSubtitleUrl(String url) {
        return url != null && !url.isBlank() && !"undefined".equalsIgnoreCase(url.trim());
    }

    private JsonNode getBiliJson(String url, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.bilibili.com/")
                .GET();
        if (cookie != null && !cookie.isBlank()) request.header("Cookie", cookie);
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("B站接口返回 HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode requireBiliData(JsonNode response, String operation) {
        int code = response.path("code").asInt(-1);
        if (code != 0) {
            String message = response.path("message").asText("未知错误");
            throw new IllegalStateException(operation + "失败：" + message + "（code=" + code + "）");
        }
        return response.path("data");
    }

    private FavService loggedInFavOrRequestLogin(String userId) {
        BiliSession session = sessions.get(userKey(userId));
        if (session == null || session.favService == null) {
            startLogin(userId, false);
            return null;
        }
        return session.favService;
    }

    private String loginRequiredMessage(String userId) {
        BiliSession session = sessions.get(userKey(userId));
        return session != null && session.pendingLogin != null
                ? "BILI_LOGIN_REQUIRED：当前用户尚未登录，B站官方二维码已发送。请等待用户用B站App扫码并确认；在用户回复“已扫码”前不要继续查询。"
                : "BILI_LOGIN_REQUIRED：当前用户尚未登录，请调用 bili_login_qr。";
    }

    private String startLogin(String userId, boolean forceRefresh) {
        BiliSession session = sessions.computeIfAbsent(userKey(userId), ignored -> new BiliSession());
        synchronized (session) {
            if (session.favService != null && !forceRefresh) {
                return "BILI_ALREADY_LOGGED_IN：当前用户已有有效的B站会话，可直接查询收藏夹。";
            }
            if (forceRefresh) closeFav(session);
            try {
                BiliQrLogin login = new BiliQrLogin();
                JsonNode qr = login.getUrl();
                String url = qr.path("data").path("url").asText();
                if (url == null || url.isBlank()) {
                    return "BILI_QR_FAILED：B站没有返回可用的登录二维码，请稍后重试。";
                }
                session.pendingLogin = login;
                session.pendingQr = qr;
                // 交由消息层发送二维码，避免写共享静态文件或误触发文生图。
                ToolExecutionContext.recordQrCode(url);
                return "BILI_QR_READY：已生成并发送B站官方登录二维码。请用B站App扫码，在手机上确认后回复“已扫码”。";
            } catch (Exception e) {
                log.warn("生成B站二维码失败, userId={}", userKey(userId), e);
                return "BILI_QR_FAILED：生成B站官方登录二维码失败（" + safeMessage(e) + "）。请稍后重试。";
            }
        }
    }

    private String handleFavFailure(String userId, String operation, Exception e) {
        String reason = safeMessage(e);
        if (isLoginFailure(reason)) {
            BiliSession session = sessions.get(userKey(userId));
            if (session != null) {
                synchronized (session) {
                    closeFav(session);
                }
            }
            startLogin(userId, false);
            return "BILI_LOGIN_EXPIRED：登录态已失效，已发送新的B站官方二维码。请等待用户扫码确认后再继续。";
        }
        log.warn("B站{}失败, userId={}, reason={}", operation, userKey(userId), reason);
        return "BILI_OPERATION_FAILED：" + operation + "失败（" + reason + "）。请向用户说明失败并建议稍后重试。";
    }

    private void clearPending(BiliSession session) {
        session.pendingLogin = null;
        session.pendingQr = null;
    }

    private void closeFav(BiliSession session) {
        if (session.favService != null) {
            try {
                session.favService.close();
            } catch (Exception e) {
                log.debug("关闭B站会话失败", e);
            }
            session.favService = null;
        }
    }

    private String userKey(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    private String truncateSubtitle(String text) {
        final int maxLength = 12000;
        return text.length() > maxLength ? text.substring(0, maxLength) + "\n…（字幕过长，已截断）" : text;
    }

    private String formatDuration(Integer seconds) {
        if (seconds == null || seconds < 0) return "未知时长";
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private boolean isExpired(String message) {
        return message != null && (message.contains("过期") || message.toLowerCase().contains("expired"));
    }

    private boolean isLoginFailure(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return message.contains("未登录") || message.contains("登录失效") || message.contains("登录过期")
                || lower.contains("login") || lower.contains("sessdata");
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(session -> {
            synchronized (session) {
                closeFav(session);
                clearPending(session);
            }
        });
        sessions.clear();
    }

    private static final class BiliSession {
        private BiliQrLogin pendingLogin;
        private JsonNode pendingQr;
        private FavService favService;
        private String cookie;
    }
}
