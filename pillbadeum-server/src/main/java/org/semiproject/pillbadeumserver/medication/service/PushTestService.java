package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 로컬 개발용 시험 알림. 로그인한 회원의 구독 브라우저로 지금 바로 1건 보낸다.
 * 복용 시각·알림 켜짐 여부와 상관없이 보내므로 local 프로필에만 둔다. 404·410 구독은 실제 발송과 같이 지운다.
 */
@Service
@Profile("local")
public class PushTestService {
    private final PushSubscriptionRepository subscriptions;
    private final WebPushSender sender;
    private final ObjectMapper json;

    public PushTestService(PushSubscriptionRepository subscriptions, WebPushSender sender, ObjectMapper json) {
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.json = json;
    }

    public record Result(int subscriptions, long sent, long failed, int removed) {}

    public Result send(long memberId) {
        List<PushSubscription> targets = subscriptions.findByMemberIdIn(List.of(memberId));
        Map<String, String> message = new LinkedHashMap<>();
        message.put("title", "필받음 · 시험 알림");
        message.put("body", "알림이 잘 도착했어요. 실제 복약 알림도 이렇게 떠요.");
        message.put("url", "/dev/push-test");
        message.put("tag", "pillbadeum-test");
        byte[] payload = json.writeValueAsBytes(message);

        long sent = 0, failed = 0;
        int removed = 0;
        for (PushSubscription subscription : targets) {
            switch (sender.send(subscription, payload, "pillbadeum-test")) {
                case SENT -> sent++;
                case GONE -> removed += subscriptions.deleteGone(subscription.getSubscriptionId(), subscription.getAuth());
                case FAILED -> failed++;
            }
        }
        return new Result(targets.size(), sent, failed, removed);
    }
}
