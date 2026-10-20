package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.medication.domain.NotificationConsentRepository;
import org.semiproject.pillbadeumserver.medication.domain.NotificationSetting;
import org.semiproject.pillbadeumserver.medication.domain.NotificationSettingRepository;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.ConsentAnswer;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingRequest;
import org.semiproject.pillbadeumserver.member.domain.Consent;
import org.semiproject.pillbadeumserver.member.domain.ConsentHistory;
import org.semiproject.pillbadeumserver.member.domain.Member;
import org.semiproject.pillbadeumserver.member.domain.MemberRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationSettingServiceTests {
    @Mock NotificationSettingRepository settings;
    @Mock NotificationConsentRepository consents;
    @Mock PushSubscriptionRepository subscriptions;
    @Mock MemberRepository members;
    NotificationSettingService service;

    // UTC 18일 23시 30분 = 한국 19일 08시 30분
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 8, 30);
    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 10, 1, 9, 0);
    private final Consent terms = new Consent(4L, "MEDICATION_REMINDER", "1.0", "복약 알림 수신 동의", "본문",
            false, true, EARLIER, EARLIER);

    @BeforeEach
    void setUp() {
        service = new NotificationSettingService(settings, consents, subscriptions, members,
                Clock.fixed(Instant.parse("2026-10-18T23:30:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 동의가_없던_회원이_동의하고_켜면_동의_이력과_함께_켜진다() {
        NotificationSetting setting = setting(false);
        noHistory();
        when(members.getReferenceById(7L)).thenReturn(new Member());
        when(consents.save(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.change(7, new SettingRequest(true, new ConsentAnswer(true, "1.0")));

        assertThat(result.pushEnabled()).isTrue();
        assertThat(result.consentAgreedAt()).isEqualTo(NOW);
        assertThat(result.consentRevokedAt()).isNull();
        assertThat(setting.isPushEnabled()).isTrue();
        assertThat(setting.getUpdatedAt()).isEqualTo(NOW);
        ConsentHistory saved = savedHistory();
        assertThat(saved.getConsentStatus()).isEqualTo("AGREED");
        assertThat(saved.getConsent()).isSameAs(terms);
        assertThat(saved.getRecordedAt()).isEqualTo(NOW);
        verify(settings).insertIfAbsent(7L, NOW);
        verify(subscriptions, never()).deleteAllOfMember(anyLong());
    }

    @Test
    void 동의_없이_켜면_409이고_아무것도_바꾸지_않는다() {
        NotificationSetting setting = setting(false);
        noHistory();

        assertConsentRequired(() -> service.change(7, new SettingRequest(true, null)));
        assertConsentRequired(() -> service.change(7, new SettingRequest(true, new ConsentAnswer(false, "1.0"))));

        assertThat(setting.isPushEnabled()).isFalse();
        verify(consents, never()).save(any());
    }

    @Test
    void 예전_약관_버전으로_동의하면_다시_동의받는다() {
        setting(false);
        noHistory();
        assertConsentRequired(() -> service.change(7, new SettingRequest(true, new ConsentAnswer(true, "0.9"))));
        verify(consents, never()).save(any());
    }

    @Test
    void 가입_때_동의한_회원은_새_이력_없이_바로_켜진다() {
        NotificationSetting setting = setting(false);
        history("AGREED", EARLIER);

        var result = service.change(7, new SettingRequest(true, null));

        assertThat(result.pushEnabled()).isTrue();
        assertThat(result.consentAgreedAt()).isEqualTo(EARLIER);
        assertThat(setting.isPushEnabled()).isTrue();
        verify(consents, never()).save(any());
    }

    @Test
    void 끄면_동의_철회를_기록하고_모든_브라우저_구독을_지운다() {
        NotificationSetting setting = setting(true);
        history("AGREED", EARLIER);
        when(members.getReferenceById(7L)).thenReturn(new Member());
        when(consents.save(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.change(7, new SettingRequest(false, null));

        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.consentAgreedAt()).isNull();
        assertThat(result.consentRevokedAt()).isEqualTo(NOW);
        assertThat(setting.isPushEnabled()).isFalse();
        assertThat(savedHistory().getConsentStatus()).isEqualTo("WITHDRAWN");
        verify(subscriptions).deleteAllOfMember(7L);
    }

    @Test
    void 이미_꺼져_있으면_새_이력_없이_그대로_성공한다() {
        NotificationSetting setting = setting(false);
        ReflectionTestUtils.setField(setting, "updatedAt", EARLIER);
        history("WITHDRAWN", EARLIER);

        var result = service.change(7, new SettingRequest(false, null));

        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.consentRevokedAt()).isEqualTo(EARLIER);
        assertThat(setting.getUpdatedAt()).isEqualTo(EARLIER);
        verify(consents, never()).save(any());
    }

    @Test
    void 끈_뒤_다시_켜면_이전_철회_이력은_두고_새_동의_행을_만든다() {
        setting(false);
        history("WITHDRAWN", EARLIER);
        when(members.getReferenceById(7L)).thenReturn(new Member());
        when(consents.save(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.change(7, new SettingRequest(true, new ConsentAnswer(true, "1.0")));

        assertThat(result.consentAgreedAt()).isEqualTo(NOW);
        assertThat(savedHistory().getConsentStatus()).isEqualTo("AGREED");
    }

    @Test
    void 조회는_켜짐과_동의_상태와_구독_수를_돌려준다() {
        when(consents.findCurrentTerms(eq(NOW), any())).thenReturn(List.of(terms));
        when(settings.findByMemberId(7L)).thenReturn(Optional.of(newSetting(true)));
        when(consents.findLatest(eq(7L), eq(4L), any())).thenReturn(List.of(historyRow("AGREED", EARLIER)));
        when(subscriptions.countByMemberId(7L)).thenReturn(2L);

        var summary = service.get(7);

        assertThat(summary.pushEnabled()).isTrue();
        assertThat(summary.consentActive()).isTrue();
        assertThat(summary.consentPolicyVersion()).isEqualTo("1.0");
        assertThat(summary.subscriptionCount()).isEqualTo(2);
    }

    @Test
    void 설정_행이_없는_회원은_꺼진_것으로_본다() {
        when(consents.findCurrentTerms(eq(NOW), any())).thenReturn(List.of(terms));
        when(settings.findByMemberId(7L)).thenReturn(Optional.empty());
        when(consents.findLatest(eq(7L), eq(4L), any())).thenReturn(List.of());

        var summary = service.get(7);

        assertThat(summary.pushEnabled()).isFalse();
        assertThat(summary.consentActive()).isFalse();
        assertThat(summary.subscriptionCount()).isZero();
    }

    private NotificationSetting setting(boolean enabled) {
        NotificationSetting setting = newSetting(enabled);
        when(settings.findForUpdate(7L)).thenReturn(Optional.of(setting));
        when(consents.findCurrentTerms(eq(NOW), any())).thenReturn(List.of(terms));
        return setting;
    }

    private static NotificationSetting newSetting(boolean enabled) {
        NotificationSetting setting = BeanUtils.instantiateClass(NotificationSetting.class);
        ReflectionTestUtils.setField(setting, "memberId", 7L);
        ReflectionTestUtils.setField(setting, "pushEnabled", enabled);
        return setting;
    }

    private void noHistory() {
        when(consents.findLatest(eq(7L), eq(4L), any())).thenReturn(List.of());
    }

    private void history(String status, LocalDateTime at) {
        when(consents.findLatest(eq(7L), eq(4L), any())).thenReturn(List.of(historyRow(status, at)));
    }

    private ConsentHistory historyRow(String status, LocalDateTime at) {
        ConsentHistory history = new ConsentHistory(new Member(), terms, status);
        history.setRecordedAt(at);
        return history;
    }

    private ConsentHistory savedHistory() {
        ArgumentCaptor<ConsentHistory> captor = ArgumentCaptor.forClass(ConsentHistory.class);
        verify(consents).save(captor.capture());
        return captor.getValue();
    }

    private static void assertConsentRequired(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONSENT_REQUIRED));
    }
}
