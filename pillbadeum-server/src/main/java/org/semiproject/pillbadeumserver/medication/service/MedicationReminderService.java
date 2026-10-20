package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.medication.domain.MedicationReminderRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationReminderRepository.Target;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 복약 알림 보내기(NOTI-003). 한 분에 한 번만, 회원마다 알림 1건을 그 회원의 모든 구독 브라우저로 보낸다.
 * 전송 이력은 DB에 남기지 않고(발송한 분만 기록) 결과는 서버 로그로만 남긴다. 약 이름·프로필 이름은 넣지 않는다.
 */
@Service
public class MedicationReminderService {
    private static final Logger log = LoggerFactory.getLogger(MedicationReminderService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TOPIC = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final MedicationReminderRepository reminders;
    private final PushSubscriptionRepository subscriptions;
    private final WebPushSender sender;
    private final VapidKeys vapid;
    private final ObjectMapper json;
    private final Executor executor;
    private final Clock clock;

    @Autowired
    public MedicationReminderService(MedicationReminderRepository reminders, PushSubscriptionRepository subscriptions,
                                     WebPushSender sender, VapidKeys vapid, ObjectMapper json,
                                     @Qualifier("applicationTaskExecutor") Executor executor) {
        this(reminders, subscriptions, sender, vapid, json, executor, Clock.system(TimeZoneConfig.ZONE));
    }

    // 테스트는 바로 실행하는 실행기와 고정 시계를 넣는다.
    MedicationReminderService(MedicationReminderRepository reminders, PushSubscriptionRepository subscriptions,
                              WebPushSender sender, VapidKeys vapid, ObjectMapper json, Executor executor, Clock clock) {
        this.reminders = reminders;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.vapid = vapid;
        this.json = json;
        this.executor = executor;
        this.clock = clock;
    }

    private record Delivery(long memberId, PushSubscription subscription, WebPushSender.Result result) {}

    /** doseAt은 한국 시간 기준 그 분(초 0)이다. */
    public void dispatch(LocalDateTime doseAt) {
        if (!vapid.configured()) {
            log.debug("웹 푸시 키가 없어 복약 알림을 건너뜀 doseAt={}", doseAt);
            return;
        }
        if (!reminders.claim(doseAt, LocalDateTime.now(clock))) {
            log.info("이미 다른 실행이 보낸 시각이라 건너뜀 doseAt={}", doseAt);
            return;
        }
        reminders.purgeBefore(doseAt.minusDays(2));

        List<Target> targets = reminders.targets(doseAt.toLocalDate(), doseAt.format(TIME));
        if (targets.isEmpty()) return;
        Map<Long, List<PushSubscription>> byMember = subscriptions
                .findByMemberIdIn(targets.stream().map(Target::memberId).toList()).stream()
                .collect(Collectors.groupingBy(PushSubscription::getMemberId));

        // 구독마다 가상 스레드로 동시에 보낸다(COMMON-006). 각 요청은 시간 제한이 있어 오래 걸리지 않는다.
        String topic = "dose-" + doseAt.format(TOPIC);
        List<CompletableFuture<Delivery>> sending = new ArrayList<>();
        for (Target target : targets) {
            byte[] payload = payload(doseAt, target.drugCount());
            for (PushSubscription subscription : byMember.getOrDefault(target.memberId(), List.of())) {
                sending.add(CompletableFuture.supplyAsync(
                        () -> new Delivery(target.memberId(), subscription, sender.send(subscription, payload, topic)), executor)
                        .exceptionally(e -> new Delivery(target.memberId(), subscription, WebPushSender.Result.FAILED)));
            }
        }
        Map<Long, List<Delivery>> results = sending.stream().map(CompletableFuture::join)
                .collect(Collectors.groupingBy(Delivery::memberId));

        for (Target target : targets) {
            List<Delivery> deliveries = results.getOrDefault(target.memberId(), List.of());
            int removed = 0;
            for (Delivery delivery : deliveries) {
                if (delivery.result() == WebPushSender.Result.GONE) {
                    removed += subscriptions.deleteGone(delivery.subscription().getSubscriptionId(),
                            delivery.subscription().getAuth());
                }
            }
            log.info("복약 알림 doseAt={} memberId={} drugs={} subscriptions={} sent={} failed={} removed={}",
                    doseAt, target.memberId(), target.drugCount(), deliveries.size(),
                    count(deliveries, WebPushSender.Result.SENT), count(deliveries, WebPushSender.Result.FAILED), removed);
        }
    }

    /** 알림 내용: 시간대 말과 개수만 바뀐다. 누르면 그 날짜·시각의 복용 목록(DOSE-001)을 연다. */
    byte[] payload(LocalDateTime doseAt, int drugCount) {
        String date = doseAt.toLocalDate().toString();
        String time = doseAt.format(TIME);
        Map<String, String> message = new LinkedHashMap<>();
        message.put("title", "필받음 · 복약 시간이에요");
        message.put("body", period(doseAt.toLocalTime()) + "에 드실 약이 " + drugCount + "개예요. 눌러서 확인해 주세요.");
        message.put("url", "/doses?date=" + date + "&time=" + time);
        message.put("tag", "dose-" + date + "-" + time);
        return json.writeValueAsBytes(message);
    }

    /** 04:00–10:59 아침, 11:00–15:59 점심, 16:00–21:59 저녁, 그 밖은 밤. */
    static String period(LocalTime time) {
        int hour = time.getHour();
        if (hour >= 4 && hour < 11) return "아침";
        if (hour >= 11 && hour < 16) return "점심";
        if (hour >= 16 && hour < 22) return "저녁";
        return "밤";
    }

    private static long count(List<Delivery> deliveries, WebPushSender.Result result) {
        return deliveries.stream().filter(delivery -> delivery.result() == result).count();
    }
}
