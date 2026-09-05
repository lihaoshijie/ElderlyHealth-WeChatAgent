package com.elderlyhealth.agent.controller;

import com.elderlyhealth.agent.config.JwtUtil;
import com.elderlyhealth.agent.entity.*;
import com.elderlyhealth.agent.mapper.AdminMapper;
import com.elderlyhealth.agent.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class AdminController {

    // ===== 公开接口：无需登录的用户列表和知识库 =====
    @GetMapping("/public/users")
    public Map<String, Object> publicUsers() {
        List<UserInfo> list = adminMapper.listUsers();
        if (list == null) list = List.of();
        return Map.of("code", 200, "data", list);
    }

    @Autowired
    private AdminMapper adminMapper;

    @Autowired
    private RagService ragService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===== 管理员登录 =====
    @PostMapping("/admin/login")
    public Map<String, Object> adminLogin(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String stored = adminMapper.getAdminPassword(username);
        if (stored == null || !stored.equals(password)) {
            return Map.of("code", 401, "msg", "用户名或密码错误");
        }
        return Map.of("code", 200, "token", JwtUtil.createToken(username, "admin"));
    }

    // ===== 用户登录（fromUserId）=====
    @PostMapping("/user/login")
    public Map<String, Object> userLogin(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        if (userId == null || userId.isEmpty()) {
            return Map.of("code", 400, "msg", "请输入用户ID");
        }
        return Map.of("code", 200, "token", JwtUtil.createToken(userId, "user"));
    }

    // ===== 管理员: 用户列表 =====
    @GetMapping("/admin/users")
    public Map<String, Object> listUsers(@RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin") && !checkAuth(auth, "user")) return Map.of("code", 403);
        List<UserInfo> list = adminMapper.listUsers();
        if (list == null) list = List.of();
        list = list.stream().filter(u -> u != null && u.getFromUserId() != null).toList();
        return Map.of("code", 200, "data", list);
    }

    // ===== 管理员: 用户对话 =====
    @GetMapping("/admin/users/{userId}/messages")
    public Map<String, Object> getUserMessages(@PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestHeader("Authorization") String auth) {
        if (!checkAnyAuth(auth)) return Map.of("code", 403);
        List<UserMessage> msgs = adminMapper.getMessages(userId, page * 50, 50);
        return Map.of("code", 200, "data", msgs != null ? msgs : List.of());
    }

    // ===== 管理员: 用户图片 =====
    @GetMapping("/admin/users/{userId}/images")
    public Map<String, Object> getUserImages(@PathVariable String userId,
            @RequestHeader("Authorization") String auth) {
        if (!checkAnyAuth(auth)) return Map.of("code", 403);
        List<UserImage> imgs = adminMapper.getImages(userId);
        return Map.of("code", 200, "data", imgs != null ? imgs : List.of());
    }

    // ===== 管理员: 用户文件 =====
    @GetMapping("/admin/users/{userId}/files")
    public Map<String, Object> getUserFiles(@PathVariable String userId,
            @RequestHeader("Authorization") String auth) {
        if (!checkAnyAuth(auth)) return Map.of("code", 403);
        List<UserFile> f = adminMapper.getFiles(userId);
        return Map.of("code", 200, "data", f != null ? f : List.of());
    }

    // ===== 用户: 自己的对话 =====
    @GetMapping("/user/messages")
    public Map<String, Object> myMessages(@RequestParam(defaultValue = "0") int page,
            @RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403);
        List<UserMessage> m = adminMapper.getMessages(claims.get("sub").toString(), page * 50, 50);
        return Map.of("code", 200, "data", m != null ? m : List.of());
    }

    // ===== 用户: 自己的图片 =====
    @GetMapping("/user/images")
    public Map<String, Object> myImages(@RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403);
        List<UserImage> imgs = adminMapper.getImages(claims.get("sub").toString());
        return Map.of("code", 200, "data", imgs != null ? imgs : List.of());
    }

    // ===== 用户: 自己的文件 =====
    @GetMapping("/user/files")
    public Map<String, Object> myFiles(@RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403);
        List<UserFile> f = adminMapper.getFiles(claims.get("sub").toString());
        return Map.of("code", 200, "data", f != null ? f : List.of());
    }

    // ===== 管理员: 清空用户对话 =====
    @DeleteMapping("/admin/users/{userId}/messages")
    public Map<String, Object> clearMessages(@PathVariable String userId,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        adminMapper.deleteMessages(userId);
        return Map.of("code", 200, "msg", "已清空");
    }

    // ===== 管理员: 清空用户图片 =====
    @DeleteMapping("/admin/users/{userId}/images")
    public Map<String, Object> clearImages(@PathVariable String userId,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        adminMapper.deleteImages(userId);
        return Map.of("code", 200, "msg", "已清空");
    }

    // ===== 管理员: 清空用户文件 =====
    @DeleteMapping("/admin/users/{userId}/files")
    public Map<String, Object> clearFiles(@PathVariable String userId,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        adminMapper.deleteFiles(userId);
        return Map.of("code", 200, "msg", "已清空");
    }

    // ===== 批量删除消息（按ID） =====
    @PostMapping("/admin/users/{userId}/messages/batch")
    public Map<String, Object> batchDeleteMessages(@PathVariable String userId,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        List<Integer> rawIds = (List<Integer>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return Map.of("code", 400, "msg", "请选择要删除的消息");
        List<Long> ids = rawIds.stream().map(Long::valueOf).toList();
        int deleted = adminMapper.deleteMessagesBatch(userId, ids);
        return Map.of("code", 200, "msg", "已删除 " + deleted + " 条消息");
    }

    // ===== 批量删除图片（按ID） =====
    @PostMapping("/admin/users/{userId}/images/batch")
    public Map<String, Object> batchDeleteImages(@PathVariable String userId,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        List<Integer> rawIds = (List<Integer>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return Map.of("code", 400, "msg", "请选择要删除的图片");
        List<Long> ids = rawIds.stream().map(Long::valueOf).toList();
        int deleted = adminMapper.deleteImagesBatch(ids);
        return Map.of("code", 200, "msg", "已删除 " + deleted + " 张图片");
    }

    // ===== 批量删除文件（按ID） =====
    @PostMapping("/admin/users/{userId}/files/batch")
    public Map<String, Object> batchDeleteFiles(@PathVariable String userId,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        List<Integer> rawIds = (List<Integer>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return Map.of("code", 400, "msg", "请选择要删除的文件");
        List<Long> ids = rawIds.stream().map(Long::valueOf).toList();
        int deleted = adminMapper.deleteFilesBatch(ids);
        return Map.of("code", 200, "msg", "已删除 " + deleted + " 个文件");
    }

    // ===== 管理员: 更新用户昵称 =====
    @PutMapping("/admin/users/{userId}/nickname")
    public Map<String, Object> updateNickname(@PathVariable String userId,
            @RequestBody Map<String, String> body,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        String nickname = body.get("nickname");
        if (nickname == null || nickname.isEmpty()) return Map.of("code", 400, "msg", "昵称不能为空");
        adminMapper.updateNickname(userId, nickname);
        return Map.of("code", 200, "msg", "已更新");
    }

    // ===== 管理员: 删除用户及所有数据 =====
    @DeleteMapping("/admin/users/{userId}")
    public Map<String, Object> deleteUser(@PathVariable String userId,
            @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        adminMapper.deleteMessages(userId);
        adminMapper.deleteImages(userId);
        adminMapper.deleteFiles(userId);
        adminMapper.deleteUser(userId);
        return Map.of("code", 200, "msg", "已删除");
    }

    // ===== 知识库管理 =====

    @GetMapping("/public/knowledge")
    public Map<String, Object> publicKnowledge(@RequestParam(defaultValue = "") String userId) {
        try {
            List<Map<String, Object>> docs;
            if (userId != null && !userId.isEmpty()) {
                docs = jdbcTemplate.queryForList(
                    "SELECT doc_name, COUNT(*) AS chunks FROM doc_embeddings WHERE user_id = ? OR user_id IS NULL GROUP BY doc_name ORDER BY doc_name", userId);
            } else {
                docs = jdbcTemplate.queryForList(
                    "SELECT doc_name, COUNT(*) AS chunks FROM doc_embeddings GROUP BY doc_name ORDER BY doc_name");
            }
            return Map.of("code", 200, "data", docs != null ? docs : List.of());
        } catch (Exception e) {
            return Map.of("code", 200, "data", List.of());
        }
    }

    @GetMapping("/admin/knowledge/list")
    public Map<String, Object> listKnowledge(@RequestHeader("Authorization") String auth,
                                              @RequestParam(defaultValue = "") String userId) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        try {
            List<Map<String, Object>> docs;
            if (userId != null && !userId.isEmpty()) {
                docs = jdbcTemplate.queryForList(
                    "SELECT doc_name, COUNT(*) AS chunks, SUM(LENGTH(chunk_text)) AS total_chars FROM doc_embeddings WHERE user_id = ? OR user_id IS NULL GROUP BY doc_name ORDER BY doc_name", userId);
            } else {
                docs = jdbcTemplate.queryForList(
                    "SELECT doc_name, COUNT(*) AS chunks, SUM(LENGTH(chunk_text)) AS total_chars FROM doc_embeddings GROUP BY doc_name ORDER BY doc_name");
            }
            return Map.of("code", 200, "data", docs != null ? docs : List.of());
        } catch (Exception e) {
            return Map.of("code", 200, "data", List.of());
        }
    }

    @PostMapping("/admin/knowledge/upload")
    public Map<String, Object> uploadKnowledge(@RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "userId", defaultValue = "") String userId,
                                                @RequestHeader("Authorization") String auth) {
        if (!checkAnyAuth(auth)) return Map.of("code", 403);
        if (file.isEmpty()) return Map.of("code", 400, "msg", "文件为空");
        try {
            ragService.ingestDocument(file.getBytes(), file.getOriginalFilename(), userId.isEmpty() ? null : userId);
            return Map.of("code", 200, "msg", "上传成功");
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/admin/knowledge/preview/{docName}")
    public Map<String, Object> previewKnowledge(@PathVariable String docName,
                                                 @RequestHeader("Authorization") String auth) {
        if (!checkAuth(auth, "admin")) return Map.of("code", 403);
        try {
            List<String> chunks = jdbcTemplate.queryForList(
                "SELECT chunk_text FROM doc_embeddings WHERE doc_name = ? ORDER BY chunk_index", String.class, docName);
            String fullText = String.join("\n\n", chunks != null ? chunks : List.of());
            return Map.of("code", 200, "data", Map.of("name", docName, "content", fullText));
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "预览失败");
        }
    }

    @DeleteMapping("/admin/knowledge/{docName}")
    public Map<String, Object> deleteKnowledge(@PathVariable String docName,
                                                @RequestParam(defaultValue = "") String userId,
                                                @RequestHeader("Authorization") String auth) {
        if (!checkAnyAuth(auth)) return Map.of("code", 403);
        try {
            if (userId != null && !userId.isEmpty()) {
                jdbcTemplate.update("DELETE FROM doc_embeddings WHERE doc_name = ? AND (user_id = ? OR user_id IS NULL)", docName, userId);
            } else {
                jdbcTemplate.update("DELETE FROM doc_embeddings WHERE doc_name = ?", docName);
            }
            return Map.of("code", 200, "msg", "已删除");
        } catch (Exception e) {
            return Map.of("code", 500, "msg", "删除失败");
        }
    }

    private boolean checkAuth(String auth, String role) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        return claims != null && role.equals(claims.get("role"));
    }

    private boolean checkAnyAuth(String auth) {
        return checkAuth(auth, "admin") || checkAuth(auth, "user");
    }

    private String token(String auth) {
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
    }
}
