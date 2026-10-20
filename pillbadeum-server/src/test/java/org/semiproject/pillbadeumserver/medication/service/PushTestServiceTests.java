package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushTestServiceTests {
    @Mock PushSubscriptionRepository subscriptions;
    @Mock WebPushSender sender;

    @Test
    void 로그인한_회원의_모든_브라우저로_시험_알림을_보내고_없어진_구독은_지운다() {
        PushSubscription ok = row(1), gone = row(2), busy = row(3);
        when(subscriptions.findByMemberIdIn(List.of(7L))).thenReturn(List.of(ok, gone, busy));
        when(sender.send(eq(ok), any(), eq("pillbadeum-test"))).thenReturn(WebPushSender.Result.SENT);
        when(sender.send(eq(gone), any(), any())).thenReturn(WebPushSender.Result.GONE);
        when(sender.send(eq(busy), any(), any())).thenReturn(WebPushSender.Result.FAILED);
        when(subscriptions.deleteGone(2L, "auth-2")).thenReturn(1);

        var result = new PushTestService(subscriptions, sender, new ObjectMapper()).send(7);

        assertThat(result).isEqualTo(new PushTestService.Result(3, 1, 1, 1));
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(sender).send(eq(ok), payload.capture(), any());
        assertThat(new String(payload.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("필받음 · 시험 알림").contains("\"tag\":\"pillbadeum-test\"");
    }

    @Test
    void 구독이_없으면_아무것도_보내지_않는다() {
        when(subscriptions.findByMemberIdIn(List.of(7L))).thenReturn(List.of());
        assertThat(new PushTestService(subscriptions, sender, new ObjectMapper()).send(7))
                .isEqualTo(new PushTestService.Result(0, 0, 0, 0));
        verifyNoInteractions(sender);
    }

    private static PushSubscription row(long id) {
        PushSubscription row = BeanUtils.instantiateClass(PushSubscription.class);
        ReflectionTestUtils.setField(row, "subscriptionId", id);
        ReflectionTestUtils.setField(row, "auth", "auth-" + id);
        return row;
    }
}
