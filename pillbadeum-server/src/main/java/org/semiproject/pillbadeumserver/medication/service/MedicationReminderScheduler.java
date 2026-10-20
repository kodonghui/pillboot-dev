package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 매분 0초(한국 시간)에 그 분의 복약 알림을 보낸다(NOTI-003). 서버가 멈춰 있던 지난 시각은 다시 보내지 않는다.
 * 테스트처럼 스케줄러가 필요 없는 실행은 web-push.scheduler.enabled=false로 끈다.
 */
@Component
@ConditionalOnProperty(name = "web-push.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class MedicationReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(MedicationReminderScheduler.class);
    private final MedicationReminderService service;

    public MedicationReminderScheduler(MedicationReminderService service) { this.service = service; }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void run() {
        // 실행이 몇 초 늦어도 같은 분으로 본다.
        LocalDateTime doseAt = LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.MINUTES);
        try {
            service.dispatch(doseAt);
        } catch (RuntimeException e) {
            log.error("복약 알림 실행 실패 doseAt={}", doseAt, e);
        }
    }
}
