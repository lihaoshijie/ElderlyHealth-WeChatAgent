package com.elderlyhealth.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 银龄智护 · 家庭守护定时任务（M1）
 *
 * - 每晚 20:00：向每位长辈的全部守护人推送当日健康日报
 * - 每晚 21:00：对「已登记用药但今日未打卡」的长辈，向其守护人推送漏服提醒
 * 依赖 family_bind 表存在；表不可用时仅记录日志，不影响其他功能。
 */
@Slf4j
@Service
public class FamilyGuardScheduler {

    private final FamilyGuardService familyGuardService;
    private final HealthManagementService healthManagementService;
    /** 记录已推送日期，防止同一天重复推送。 */
    private final ConcurrentHashMap<String, String> lastReportDate = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastMissDate = new ConcurrentHashMap<>();

    public FamilyGuardScheduler(FamilyGuardService familyGuardService,
                                HealthManagementService healthManagementService) {
        this.familyGuardService = familyGuardService;
        this.healthManagementService = healthManagementService;
    }

    @Scheduled(cron = "${family.daily-report-cron:0 0 20 * * *}")
    public void pushDailyReports() {
        List<String> elders = familyGuardService.listEldersWithActiveGuardians();
        String today = LocalDate.now().toString();
        for (String elderId : elders) {
            if (today.equals(lastReportDate.get(elderId))) continue;
            try {
                String preview = familyGuardService.sendDailyReportToGuardians(elderId);
                lastReportDate.put(elderId, today);
                log.info("健康日报推送完成 elder={}", elderId);
            } catch (Exception e) {
                log.warn("健康日报推送失败 elder={}", elderId, e);
            }
        }
    }

    @Scheduled(cron = "${family.missed-med-cron:0 0 21 * * *}")
    public void pushMissedMedicationAlerts() {
        List<String> elders = familyGuardService.listEldersWithActiveGuardians();
        String today = LocalDate.now().toString();
        for (String elderId : elders) {
            if (today.equals(lastMissDate.get(elderId))) continue;
            try {
                List<String> missed = healthManagementService.listMedicationsNotTakenToday(elderId);
                if (missed.isEmpty()) continue;
                String content = "💊 漏服提醒：家人今日尚未打卡的药物：" + String.join("、", missed)
                        + "。请提醒长辈按时服药；如确实已服用请帮忙打卡。";
                List<String> guardians = familyGuardService.listGuardianIds(elderId);
                int ok = 0;
                for (String gid : guardians) {
                    if (familyGuardService.sendToGuardian(gid, content)) ok++;
                }
                lastMissDate.put(elderId, today);
                log.info("漏服提醒已推送 elder={}, guardians={}, missed={}", elderId, ok, missed);
            } catch (Exception e) {
                log.warn("漏服提醒推送失败 elder={}", elderId, e);
            }
        }
    }
}
