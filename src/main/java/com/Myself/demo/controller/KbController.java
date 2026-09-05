package com.Myself.demo.controller;

import com.Myself.demo.config.JwtUtil;
import com.Myself.demo.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/kb")
public class KbController {

    @Value("${kb.admin-password:}")
    private String adminPassword;

    @Value("${kb.guest-password:}")
    private String guestPassword;

    private final RagService ragService;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public KbController(RagService ragService) {
        this.ragService = ragService;
    }

    // ===== 管理员登录 =====
    @PostMapping("/admin/login")
    public Map<String, Object> adminLogin(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.trim().isEmpty()) return Map.of("code", 400, "msg", "请输入用户名");
        if (adminPassword.isEmpty()) return Map.of("code", 500, "msg", "未配置管理员口令(kb.admin-password)");
        if (!adminPassword.equals(password)) return Map.of("code", 401, "msg", "口令错误");
        return Map.of("code", 200, "token", JwtUtil.createToken(username.trim(), "admin"), "role", "admin");
    }

    // ===== 普通用户登录（昵称 + 访客口令）=====
    @PostMapping("/login")
    public Map<String, Object> guestLogin(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String password = body.get("password");
        if (name == null || name.trim().isEmpty()) return Map.of("code", 400, "msg", "请输入昵称");
        if (guestPassword.isEmpty()) return Map.of("code", 500, "msg", "未配置访客口令(kb.guest-password)");
        if (!guestPassword.equals(password)) return Map.of("code", 401, "msg", "口令错误");
        return Map.of("code", 200, "token", JwtUtil.createToken(name.trim(), "user"), "role", "user");
    }

    // ===== 知识库列表 =====
    @GetMapping("/documents")
    public Map<String, Object> documents(@RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403, "msg", "请先登录");
        boolean isAdmin = "admin".equals(claims.get("role"));
        List<Map<String, Object>> docs = ragService.listDocuments();
        if (!isAdmin) {
            for (Map<String, Object> d : docs) {
                d.remove("uploader");
            }
        }
        return Map.of("code", 200, "data", docs, "role", claims.get("role"));
    }

    // ===== 搜索文档（所有人可搜）=====
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q,
            @RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403, "msg", "请先登录");
        boolean isAdmin = "admin".equals(claims.get("role"));
        List<Map<String, Object>> result = ragService.searchDocuments(q);
        if (!isAdmin) {
            for (Map<String, Object> r : result) r.remove("uploader");
        }
        return Map.of("code", 200, "data", result);
    }

    // ===== 文档详情（所有人可看）=====
    @GetMapping("/documents/{name}/detail")
    public Map<String, Object> detail(@PathVariable String name,
            @RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403, "msg", "请先登录");
        return Map.of("code", 200, "data", ragService.getDocumentChunks(name));
    }

    // ===== 上传入库（仅管理员）=====
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
            @RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403, "msg", "请先登录");
        if (!"admin".equals(claims.get("role"))) return Map.of("code", 403, "msg", "仅管理员可上传");
        if (file == null || file.isEmpty()) return Map.of("code", 400, "msg", "文件为空");
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.trim().isEmpty()) return Map.of("code", 400, "msg", "文件名无效");
        try {
            byte[] bytes = file.getBytes();
            String uploader = String.valueOf(claims.get("sub"));
            executor.submit(() -> ragService.ingestDocument(bytes, fileName, uploader));
            return Map.of("code", 200, "msg", "入库中，稍后刷新可见", "name", fileName);
        } catch (Exception e) {
            log.error("上传失败: {}", fileName, e);
            return Map.of("code", 500, "msg", "上传失败: " + e.getMessage());
        }
    }

    // ===== 删除文档（仅管理员）=====
    @DeleteMapping("/documents/{name}")
    public Map<String, Object> delete(@PathVariable String name,
            @RequestHeader("Authorization") String auth) {
        Map<String, Object> claims = JwtUtil.verify(token(auth));
        if (claims == null) return Map.of("code", 403, "msg", "请先登录");
        if (!"admin".equals(claims.get("role"))) return Map.of("code", 403, "msg", "仅管理员可删除");
        int n = ragService.deleteDocument(name);
        return Map.of("code", 200, "msg", "已删除 " + n + " 条记录");
    }

    private String token(String auth) {
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
    }
}
