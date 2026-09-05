package com.elderlyhealth.agent.service;

import com.elderlyhealth.agent.bot.WeChatBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 银龄智护 · 家庭联动服务（M1）
 *
 * 数据模型（表 family_bind）：
 * - 长辈（elder_user_id）生成邀请码，子女/守护人（guardian_user_id）对 bot 发送绑定码完成绑定；
 * - 状态：INVITED（待确认）/ ACTIVE（已生效）/ REMOVED（已解绑）。
 *
 * 依赖说明：使用 DB 表持久化绑定关系；推送复用 WeChatBotService（iLink sendText）。
 */
@Slf4j
@Service
public class FamilyGuardService {

    private final JdbcTemplate jdbcTemplate;
    private final HealthManagementService healthManagementService;
    private final WeChatBotService weChatBotService;

    public FamilyGuardService(JdbcTemplate jdbcTemplate,
                              HealthManagementService healthManagementService,
                              WeChatBotService weChatBotService) {
        this.jdbcTemplate = jdbcTemplate;
        this.healthManagementService = healthManagementService;
        this.weChatBotService = weChatBotService;
    }

    // ---------- 绑定流程 ----------

    /** 长辈生成 6 位邀请码（同一长辈旧的待确认码作废）。 */
    public String createInvite(String elderUserId) {
        if (elderUserId == null || elderUserId.isBlank()) return "无法识别当前用户，请稍后再试。";
        try {
            // 先清理该长辈历史未确认码
            jdbcTemplate.update("UPDATE family_bind SET status='REMOVED' WHERE elder_user_id=? AND status='INVITED'", elderUserId);
            String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
            jdbcTemplate.update(
                "INSERT INTO family_bind (elder_user_id, guardian_user_id, invite_code, relation, status) VALUES (?, NULL, ?, '子女', 'INVITED')",
                elderUserId, code);
            return "已生成家庭绑定邀请码：" + code
                + "\n\n请把下面这段话发给您的子女，让 ta 转发给本助手：\n"
                + "「绑定家人 " + code + "」"
                + "\n\n（绑定后，我每天会把您的健康日报和异常提醒推送给 ta）";
        } catch (Exception e) {
            log.error("生成家庭绑定码失败 elderUserId={}", elderUserId, e);
            return "生成绑定码失败（数据库不可用？），请稍后再试。";
        }
    }

    /** 守护人（子女）输入邀请码完成绑定。 */
    public String acceptInvite(String guardianUserId, String code) {
        if (guardianUserId == null || guardianUserId.isBlank()) return "无法识别当前用户，请稍后再试。";
        if (code == null || !code.matches("\\d{6}")) return "绑定码应为 6 位数字，请核对后再发一次。";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, elder_user_id FROM family_bind WHERE invite_code=? AND status='INVITED' LIMIT 1", code);
            if (rows.isEmpty()) return "没有找到该绑定码，可能已过期，请让家人重新生成。";
            String elderUserId = String.valueOf(rows.get(0).get("elder_user_id"));
            if (elderUserId.equals(guardianUserId)) return "不能绑定自己，请让家人（长辈）生成绑定码后由您来确认。";
            Long id = ((Number) rows.get(0).get("id")).longValue();

            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM family_bind WHERE elder_user_id=? AND guardian_user_id=? AND status='ACTIVE'",
                Integer.class, elderUserId, guardianUserId);
            if (exists != null && exists > 0) return "您已经是这位家人的守护人，无需重复绑定。";

            jdbcTemplate.update("UPDATE family_bind SET guardian_user_id=?, status='ACTIVE' WHERE id=?", guardianUserId, id);
            log.info("家庭绑定成功 elder={}, guardian={}", elderUserId, guardianUserId);
            return "绑定成功 ✅ 从现在起，您将收到家人的健康日报与异常提醒。";
        } catch (Exception e) {
            log.error("接受绑定失败 guardianUserId={} code={}", guardianUserId, code, e);
            return "绑定失败（数据库不可用？），请稍后再试。";
        }
    }

    /** 长辈解绑某位守护人。 */
    public String unbindGuardian(String elderUserId, String guardianUserId) {
        if (guardianUserId == null || guardianUserId.isBlank()) return "请提供要解绑的守护人。";
        try {
            int n = jdbcTemplate.update(
                "UPDATE family_bind SET status='REMOVED' WHERE elder_user_id=? AND guardian_user_id=? AND status='ACTIVE'",
                elderUserId, guardianUserId);
            return n > 0 ? "已解绑该守护人。" : "没有找到生效中的绑定关系。";
        } catch (Exception e) {
            log.error("解绑失败 elder={} guardian={}", elderUserId, guardianUserId, e);
            return "解绑失败，请稍后再试。";
        }
    }

    // ---------- 查询 ----------

    /** 我作为长辈，列出守护我的人（userId 列表）。 */
    public List<String> listGuardianIds(String elderUserId) {
        try {
            return jdbcTemplate.queryForList(
                "SELECT guardian_user_id FROM family_bind WHERE elder_user_id=? AND status='ACTIVE' AND guardian_user_id IS NOT NULL",
                String.class, elderUserId);
        } catch (Exception e) {
            log.warn("查询守护人失败 elder={}", elderUserId, e);
            return List.of();
        }
    }

    /** 我作为守护人，列出我守护的长辈（userId 列表）。 */
    public List<String> listElderIds(String guardianUserId) {
        try {
            return jdbcTemplate.queryForList(
                "SELECT elder_user_id FROM family_bind WHERE guardian_user_id=? AND status='ACTIVE'",
                String.class, guardianUserId);
        } catch (Exception e) {
            log.warn("查询被守护长辈失败 guardian={}", guardianUserId, e);
            return List.of();
        }
    }

    /** 有至少一位 ACTIVE 守护人的长辈列表（调度推送用）。 */
    public List<String> listEldersWithActiveGuardians() {
        try {
            return jdbcTemplate.queryForList(
                "SELECT DISTINCT elder_user_id FROM family_bind WHERE status='ACTIVE' AND guardian_user_id IS NOT NULL",
                String.class);
        } catch (Exception e) {
            log.warn("查询有守护人的长辈失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 长辈视角：守护我的人展示文本。 */
    public String describeMyGuardians(String elderUserId) {
        List<String> ids = listGuardianIds(elderUserId);
        if (ids.isEmpty()) return "您还没有绑定守护人。\n发送「绑定家人」即可生成邀请码，让子女绑定后接收您的健康日报。";
        StringBuilder sb = new StringBuilder("👨‍👩‍👧 我的守护人（").append(ids.size()).append(" 位）\n");
        for (String id : ids) sb.append("· ").append(id).append("\n");
        sb.append("\n如需解绑，请告诉我守护人的完整 ID。");
        return sb.toString();
    }

    /** 守护人视角：我守护的长辈展示文本。 */
    public String describeMyElders(String guardianUserId) {
        List<String> ids = listElderIds(guardianUserId);
        if (ids.isEmpty()) return "您当前没有守护的长辈。请让家人先在助手侧生成绑定码，再对助手发送「绑定家人 6位码」。";
        StringBuilder sb = new StringBuilder("👴 您守护的长辈（").append(ids.size()).append(" 位）\n");
        for (String id : ids) sb.append("· ").append(id).append("\n");
        return sb.toString();
    }

    // ---------- 健康日报 / 通知 ----------

    /** 为某位长辈生成今日健康日报正文。 */
    public String buildDailyReport(String elderUserId) {
        String date = LocalDate.now().toString();
        StringBuilder sb = new StringBuilder("📋 家人健康日报  ").append(date).append("\n");
        try {
            sb.append(healthManagementService.dailyBriefing(elderUserId, null)).append("\n\n");
        } catch (Exception e) {
            log.warn("日报-基础简报失败 elder={}", elderUserId, e);
            sb.append("（健康档案读取失败）\n");
        }
        try {
            sb.append("🍚 ").append(healthManagementService.dailyDietarySummary(elderUserId)).append("\n");
        } catch (Exception e) {
            log.debug("日报-饮食摘要为空 elder={}", elderUserId);
        }
        try {
            String adh = healthManagementService.medicationAdherenceSummary(elderUserId, 7);
            if (adh != null && !adh.isBlank()) sb.append("💊 近 7 日服药：").append(adh).append("\n");
        } catch (Exception e) {
            log.debug("日报-依从性为空 elder={}", elderUserId);
        }
        return sb.toString().trim();
    }

    /** 长辈请求：把今日日报推送给所有生效守护人；返回带预览的结果文本。 */
    public String sendDailyReportToGuardians(String elderUserId) {
        List<String> guardians = listGuardianIds(elderUserId);
        String report = buildDailyReport(elderUserId);
        if (guardians.isEmpty()) {
            return "您还没有绑定守护人。发送「绑定家人」生成邀请码。\n\n日报预览：\n" + report;
        }
        int ok = 0;
        for (String gid : guardians) {
            if (weChatBotService.sendGuardianMessage(gid, "【家人健康日报】\n" + report)) ok++;
        }
        return ok > 0
            ? "✅ 已向 " + ok + "/" + guardians.size() + " 位守护人推送今日健康日报。\n\n日报内容：\n" + report
            : "推送失败，请稍后再试（守护人可能需先与助手会话一次）。\n\n日报内容：\n" + report;
    }

    /** 体征红色预警：立即通知所有生效守护人（带长辈 ID 与建议）。 */
    public boolean notifyGuardiansOfVitalAlert(String elderUserId, String type, String value, String level, String advice) {
        List<String> guardians = listGuardianIds(elderUserId);
        if (guardians.isEmpty()) return false;
        int ok = 0;
        for (String gid : guardians) {
            String msg = "⚠️ 健康预警（家人 " + elderUserId + "）\n"
                + (level.equals("RED") ? "【红色 · 请尽快关注】\n" : "【黄色 · 建议关注】\n")
                + "指标：" + type + " = " + value + "\n" + advice
                + "\n\n请及时联系长辈确认情况，必要时建议就医。";
            if (weChatBotService.sendGuardianMessage(gid, msg)) ok++;
        }
        return ok > 0;
    }

    /** 通用文本：给某守护人直接发消息。 */
    public boolean sendToGuardian(String guardianUserId, String content) {
        return weChatBotService.sendGuardianMessage(guardianUserId, content);
    }
}
