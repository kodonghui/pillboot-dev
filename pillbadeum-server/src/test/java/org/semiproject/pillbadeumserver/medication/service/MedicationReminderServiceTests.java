package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.medication.domain.MedicationReminderRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationReminderRepository.Target;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationReminderServiceTests {
    private static final LocalDateTime SEVEN = LocalDateTime.of(2026, 10, 16, 7, 0);
    @Mock MedicationReminderRepository reminders;
    @Mock PushSubscriptionRepository subscriptions;
    @Mock WebPushSender sender;
    private final ObjectMapper json = new ObjectMapper();
    MedicationReminderService service;

    @BeforeEach
    void setUp() {
        service = service(PushTestKeys.vapid());
    }

    @Test
    void 같은_회원의_같은_시각_약은_알림_한_건으로_묶어_모든_브라우저에_보낸다() {
        claimed();
        // 본인 약 2개 + 가족 약 1개 = 회원 7의 약 3개
        when(reminders.targets(LocalDate.of(2026, 10, 16), "07:00")).thenReturn(List.of(new Target(7, 3)));
        PushSubscription laptop = subscription(1, 7), phone = subscription(2, 7);
        when(subscriptions.findByMemberIdIn(List.of(7L))).thenReturn(List.of(laptop, phone));
        when(sender.send(any(), any(), any())).thenReturn(WebPushSender.Result.SENT);

        service.dispatch(SEVEN);

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(sender).send(eq(laptop), payloads.capture(), eq("dose-20261016-0700"));
        verify(sender).send(eq(phone), payloads.capture(), eq("dose-20261016-0700"));
        assertThat(payloads.getAllValues().get(0)).isEqualTo(payloads.getAllValues().get(1));
        JsonNode message = json.readTree(payloads.getValue());
        assertThat(message.path("title").asString()).isEqualTo("필받음 · 복약 시간이에요");
        assertThat(message.path("body").asString()).isEqualTo("아침에 드실 약이 3개예요. 눌러서 확인해 주세요.");
        assertThat(message.path("url").asString()).isEqualTo("/doses?date=2026-10-16&time=07:00");
        assertThat(message.path("tag").asString()).isEqualTo("dose-2026-10-16-07:00");
        verify(subscriptions, never()).deleteGone(any(), any());
    }

    @Test
    void 이미_다른_실행이_맡은_분은_아무것도_보내지_않는다() {
        when(reminders.claim(eq(SEVEN), any())).thenReturn(false);
        service.dispatch(SEVEN);
        verify(reminders, never()).targets(any(), any());
        verifyNoInteractions(sender, subscriptions);
    }

    @Test
    void 서버_키가_없으면_분을_맡지도_않는다() {
        service(new VapidKeys("", "", "")).dispatch(SEVEN);
        verifyNoInteractions(reminders, sender, subscriptions);
    }

    @Test
    void 구독이_없어진_브라우저만_지우고_다른_실패는_남긴다() {
        claimed();
        when(reminders.targets(any(), any())).thenReturn(List.of(new Target(7, 1)));
        PushSubscription gone = subscription(1, 7), busy = subscription(2, 7);
        when(subscriptions.findByMemberIdIn(List.of(7L))).thenReturn(List.of(gone, busy));
        when(sender.send(eq(gone), any(), any())).thenReturn(WebPushSender.Result.GONE);
        when(sender.send(eq(busy), any(), any())).thenReturn(WebPushSender.Result.FAILED);

        service.dispatch(SEVEN);

        verify(subscriptions).deleteGone(1L, "auth-1");
        verify(subscriptions, never()).deleteGone(eq(2L), any());
    }

    @Test
    void 한_브라우저에서_예외가_나도_다른_회원_발송은_계속된다() {
        claimed();
        when(reminders.targets(any(), any())).thenReturn(List.of(new Target(7, 1), new Target(8, 2)));
        PushSubscription broken = subscription(1, 7), other = subscription(2, 8);
        when(subscriptions.findByMemberIdIn(List.of(7L, 8L))).thenReturn(List.of(broken, other));
        when(sender.send(eq(broken), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(sender.send(eq(other), any(), any())).thenReturn(WebPushSender.Result.SENT);

        assertThatCode(() -> service.dispatch(SEVEN)).doesNotThrowAnyException();
        verify(sender).send(eq(other), any(), any());
    }

    @Test
    void 대상이_없으면_구독도_조회하지_않고_오래된_발송_기록은_정리한다() {
        claimed();
        when(reminders.targets(any(), any())).thenReturn(List.of());
        service.dispatch(SEVEN);
        verify(reminders).purgeBefore(SEVEN.minusDays(2));
        verifyNoInteractions(subscriptions, sender);
    }

    @Test
    void 시간대_말은_명세의_경계를_따른다() {
        assertThat(MedicationReminderService.period(LocalTime.of(3, 59))).isEqualTo("밤");
        assertThat(MedicationReminderService.period(LocalTime.of(4, 0))).isEqualTo("아침");
        assertThat(MedicationReminderService.period(LocalTime.of(10, 59))).isEqualTo("아침");
        assertThat(MedicationReminderService.period(LocalTime.of(11, 0))).isEqualTo("점심");
        assertThat(MedicationReminderService.period(LocalTime.of(16, 0))).isEqualTo("저녁");
        assertThat(MedicationReminderService.period(LocalTime.of(21, 59))).isEqualTo("저녁");
        assertThat(MedicationReminderService.period(LocalTime.of(22, 0))).isEqualTo("밤");
    }

    @Test
    void 알림_내용에는_약_이름이_없다() {
        String body = new String(service.payload(LocalDateTime.of(2026, 10, 16, 22, 30), 2),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("밤에 드실 약이 2개예요").contains("time=22:30").doesNotContain("타이레놀");
    }

    private MedicationReminderService service(VapidKeys keys) {
        return new MedicationReminderService(reminders, subscriptions, sender, keys, json, Runnable::run,
                Clock.fixed(Instant.parse("2026-10-15T22:00:01Z"), ZoneId.of("Asia/Seoul")));
    }

    private void claimed() {
        when(reminders.claim(eq(SEVEN), any())).thenReturn(true);
    }

    private static PushSubscription subscription(long id, long memberId) {
        PushSubscription row = BeanUtils.instantiateClass(PushSubscription.class);
        ReflectionTestUtils.setField(row, "subscriptionId", id);
        ReflectionTestUtils.setField(row, "memberId", memberId);
        ReflectionTestUtils.setField(row, "auth", "auth-" + id);
        return row;
    }
}
