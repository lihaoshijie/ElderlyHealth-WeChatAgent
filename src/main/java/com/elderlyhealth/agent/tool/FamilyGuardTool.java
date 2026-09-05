package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.FamilyGuardService;
import com.elderlyhealth.agent.service.HealthManagementService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 银龄智护 · 家庭联动与健康守护工具（M1）
 *
 * 场景：长辈在微信里生成绑定码 → 子女对同一助手发送绑定码完成绑定 →
 * 系统向子女推送长辈健康日报与红色体征预警、漏服提醒。
 */
@Component
public class FamilyGuardTool {

    private final FamilyGuardService familyGuardService;
    private final HealthManagementService healthManagementService;

    public FamilyGuardTool(FamilyGuardService familyGuardService,
                           HealthManagementService healthManagementService) {
        this.familyGuardService = familyGuardService;
        this.healthManagementService = healthManagementService;
    }

    @Tool(name = "family_bind_start", value = "长辈发起家庭守护绑定：生成 6 位邀请码，子女凭码绑定后即可接收长辈健康日报与预警。用户表示想要子女绑定/守护/子女查看健康时调用。")
    public String bindStart(@P("用户ID，系统自动填充") String userId) {
        return familyGuardService.createInvite(userId);
    }

    @Tool(name = "family_bind_confirm", value = "子女/守护人输入 6 位绑定码，把自己绑定为某位长辈的守护人。用户发送形如「绑定家人 123456」的 6 位码时调用。")
    public String bindConfirm(@P("长辈生成的 6 位绑定码") String code,
                              @P("用户ID，系统自动填充") String userId) {
        return familyGuardService.acceptInvite(userId, code == null ? "" : code.trim());
    }

    @Tool(name = "family_guardians", value = "长辈查看当前守护自己的家人列表（用于了解绑定状态或准备解绑）。")
    public String listGuardians(@P("用户ID，系统自动填充") String userId) {
        return familyGuardService.describeMyGuardians(userId);
    }

    @Tool(name = "family_elders", value = "守护人查看自己守护了哪些长辈（子女视角）。")
    public String listElders(@P("用户ID，系统自动填充") String userId) {
        return familyGuardService.describeMyElders(userId);
    }

    @Tool(name = "family_unbind", value = "长辈解绑某位守护人，参数为守护人的用户ID（先调用 family_guardians 查看 ID）。")
    public String unbind(@P("要解绑的守护人用户ID") String guardianUserId,
                         @P("用户ID，系统自动填充") String userId) {
        return familyGuardService.unbindGuardian(userId, guardianUserId);
    }

    @Tool(name = "family_daily_report", value = "立即向所有已绑定的守护人（子女）推送今天的家人健康日报；长辈说「给子女发日报/今天的日报发给孩子们」时调用。")
    public String dailyReport(@P("用户ID，系统自动填充") String userId) {
        return familyGuardService.sendDailyReportToGuardians(userId);
    }

    @Tool(name = "medication_adherence_report", value = "用药依从性分析：统计最近 N 天（默认7天，最长90天）的实际服药打卡天数与依从率，识别漏服风险。用户询问「依从性/服药率/有没有按时吃药/漏服」时调用。")
    public String adherence(@P("统计天数，默认7，最长90") int days,
                            @P("用户ID，系统自动填充") String userId) {
        return healthManagementService.medicationAdherenceReport(userId, days);
    }
}
