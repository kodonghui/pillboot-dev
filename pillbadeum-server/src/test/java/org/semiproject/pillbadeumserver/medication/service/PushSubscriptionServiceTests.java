package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SubscriptionKeys;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SubscriptionRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTests {
    private static final String HOSTS = "fcm.googleapis.com,updates.push.services.mozilla.com,.notify.windows.com";
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/dXN1YmlkOkFQQTkxYkg";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 8, 30);
    @Mock PushSubscriptionRepository subscriptions;
    PushSubscriptionService service;
    private final String p256dh = PushTestKeys.publicText(PushTestKeys.generate());
    private final String auth = PushTestKeys.encode(new byte[16]);

    @BeforeEach
    void setUp() {
        service = new PushSubscriptionService(subscriptions, PushTestKeys.vapid(), HOSTS,
                Clock.fixed(Instant.parse("2026-10-18T23:30:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 처음_보는_브라우저는_새로_저장한다() {
        when(subscriptions.findByEndpoint(FCM)).thenReturn(Optional.empty(), Optional.of(row(11)));

        var saved = service.save(7, request(FCM, p256dh, auth, "Chrome"));

        assertThat(saved.created()).isTrue();
        assertThat(saved.subscriptionId()).isEqualTo(11);
        verify(subscriptions).upsert(7, FCM, p256dh, auth, "Chrome", NOW);
    }

    @Test
    void 같은_브라우저를_다시_보내면_고치고_다른_회원_것이면_지금_회원으로_옮긴다() {
        when(subscriptions.findByEndpoint(FCM)).thenReturn(Optional.of(row(11)));

        var saved = service.save(8, request(FCM, p256dh, auth, null));

        assertThat(saved.created()).isFalse();
        assertThat(saved.subscriptionId()).isEqualTo(11);
        verify(subscriptions).upsert(8, FCM, p256dh, auth, null, NOW);
    }

    @Test
    void 허용한_푸시_서비스가_아닌_주소는_저장하지_않는다() {
        for (String endpoint : new String[]{
                "http://fcm.googleapis.com/fcm/send/a",
                "https://fcm.googleapis.com.evil.test/fcm/send/a",
                "https://evil.test/fcm.googleapis.com",
                "https://user@fcm.googleapis.com/fcm/send/a",
                "https://fcm.googleapis.com:8443/fcm/send/a",
                "https://notify.windows.com/w",
                "https://localhost/push",
                "https://fcm.googleapis.com/fcm/send/한글"}) {
            assertInvalid(() -> service.save(7, request(endpoint, p256dh, auth, null)));
        }
        verifyNoInteractions(subscriptions);
    }

    @Test
    void 하위_주소를_허용한_서비스는_그_아래_주소만_받는다() {
        String wns = "https://wns2-par02p.notify.windows.com/w/?token=abc";
        when(subscriptions.findByEndpoint(wns)).thenReturn(Optional.empty(), Optional.of(row(12)));
        assertThat(service.save(7, request(wns, p256dh, auth, null)).created()).isTrue();
    }

    @Test
    void 브라우저_키_형식이_틀리면_저장하지_않는다() {
        assertInvalid(() -> service.save(7, request(FCM, "short", auth, null)));
        assertInvalid(() -> service.save(7, request(FCM, p256dh, PushTestKeys.encode(new byte[15]), null)));
        assertInvalid(() -> service.save(7, request(FCM, p256dh, "***", null)));
        verifyNoInteractions(subscriptions);
    }

    @Test
    void 긴_브라우저_정보는_500자로_자른다() {
        when(subscriptions.findByEndpoint(FCM)).thenReturn(Optional.empty(), Optional.of(row(11)));
        service.save(7, request(FCM, p256dh, auth, "a".repeat(800)));
        verify(subscriptions).upsert(eq(7L), eq(FCM), eq(p256dh), eq(auth), argThat(ua -> ua.length() == 500), eq(NOW));
    }

    @Test
    void 서버_키가_없으면_구독을_받지_않는다() {
        PushSubscriptionService unconfigured = new PushSubscriptionService(subscriptions, new VapidKeys("", "", ""), HOSTS,
                Clock.systemUTC());
        assertThatThrownBy(() -> unconfigured.save(7, request(FCM, p256dh, auth, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PUSH_UNAVAILABLE));
        verifyNoInteractions(subscriptions);
    }

    private static SubscriptionRequest request(String endpoint, String p256dh, String auth, String userAgent) {
        return new SubscriptionRequest(endpoint, new SubscriptionKeys(p256dh, auth), userAgent);
    }

    private static PushSubscription row(long id) {
        PushSubscription row = BeanUtils.instantiateClass(PushSubscription.class);
        ReflectionTestUtils.setField(row, "subscriptionId", id);
        return row;
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
