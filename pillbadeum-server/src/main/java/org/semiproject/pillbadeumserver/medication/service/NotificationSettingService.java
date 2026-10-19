package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.medication.domain.NotificationConsentRepository;
import org.semiproject.pillbadeumserver.medication.domain.NotificationSetting;
import org.semiproject.pillbadeumserver.medication.domain.NotificationSettingRepository;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.ConsentAnswer;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingRequest;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingResponse;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingSummary;
import org.semiproject.pillbadeumserver.member.domain.Consent;
import org.semiproject.pillbadeumserver.member.domain.ConsentHistory;
import org.semiproject.pillbadeumserver.member.domain.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * 알림 켜기·끄기(NOTI-001, NOTI-002). 알림 끄기는 알림 수신 동의 철회와 같이 움직인다.
 * 켜기·끄기는 회원의 설정 행을 잠근 한 트랜잭션에서 설정·동의 이력·구독을 함께 바꾼다.
 */
@Service
@Transactional(readOnly = true)
public class NotificationSettingService {
    static final String AGREED = "AGREED";
    static final String WITHDRAWN = "WITHDRAWN";

    private final NotificationSettingRepository settings;
    private final NotificationConsentRepository consents;
    private final PushSubscriptionRepository subscriptions;
    private final MemberRepository members;
    private final Clock clock;

    @Autowired
    public NotificationSettingService(NotificationSettingRepository settings, NotificationConsentRepository consents,
                                      PushSubscriptionRepository subscriptions, MemberRepository members) {
        this(settings, consents, subscriptions, members, Clock.system(TimeZoneConfig.ZONE));
    }

    // 테스트는 고정 시계로 동의·철회 시각을 확인한다.
    NotificationSettingService(NotificationSettingRepository settings, NotificationConsentRepository consents,
                               PushSubscriptionRepository subscriptions, MemberRepository members, Clock clock) {
        this.settings = settings;
        this.consents = consents;
        this.subscriptions = subscriptions;
        this.members = members;
        this.clock = clock;
    }

    public SettingSummary get(long memberId) {
        Optional<Consent> terms = currentTerms(LocalDateTime.now(clock));
        boolean pushEnabled = settings.findByMemberId(memberId).map(NotificationSetting::isPushEnabled).orElse(false);
        boolean consentActive = terms.flatMap(t -> latest(memberId, t)).map(h -> AGREED.equals(h.getConsentStatus()))
                .orElse(false);
        return new SettingSummary(pushEnabled, consentActive, terms.map(Consent::getTermsVersion).orElse(null),
                subscriptions.countByMemberId(memberId));
    }

    @Transactional
    public SettingResponse change(long memberId, SettingRequest request) {
        // DATETIME은 초 아래를 반올림해 저장하므로 응답과 DB 값이 같도록 초까지만 쓴다.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        settings.insertIfAbsent(memberId, now);
        NotificationSetting setting = settings.findForUpdate(memberId)
                .orElseThrow(() -> new IllegalStateException("알림 설정 행을 만들지 못했습니다. memberId=" + memberId));
        Optional<Consent> terms = currentTerms(now);
        Optional<ConsentHistory> latest = terms.flatMap(t -> latest(memberId, t));
        boolean consentActive = latest.map(h -> AGREED.equals(h.getConsentStatus())).orElse(false);

        if (request.pushEnabled()) {
            if (!consentActive) {
                Consent current = terms.orElseThrow(
                        () -> new IllegalStateException("시행 중인 복약 알림 수신 약관이 없습니다."));
                requireAgreement(request.notificationConsent(), current);
                latest = Optional.of(record(memberId, current, AGREED, now));
            }
            setting.changePushEnabled(true, now);
        } else {
            if (consentActive) latest = Optional.of(record(memberId, terms.orElseThrow(), WITHDRAWN, now));
            setting.changePushEnabled(false, now);
            // 알림을 원하지 않는 회원의 브라우저 주소는 남기지 않는다. 다시 켜면 그 브라우저부터 새로 구독한다.
            subscriptions.deleteAllOfMember(memberId);
        }
        return response(setting.isPushEnabled(), latest);
    }

    private static void requireAgreement(ConsentAnswer answer, Consent terms) {
        if (answer == null || !Boolean.TRUE.equals(answer.agreed())) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED, "알림을 받으려면 알림 수신 동의가 필요해요.");
        }
        if (!Objects.equals(answer.policyVersion(), terms.getTermsVersion())) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED, "알림 수신 약관이 바뀌었어요. 최신 약관에 다시 동의해주세요.");
        }
    }

    private ConsentHistory record(long memberId, Consent terms, String status, LocalDateTime now) {
        ConsentHistory history = new ConsentHistory(members.getReferenceById(memberId), terms, status);
        history.setRecordedAt(now);
        return consents.save(history);
    }

    private Optional<Consent> currentTerms(LocalDateTime now) {
        return consents.findCurrentTerms(now, Limit.of(1)).stream().findFirst();
    }

    private Optional<ConsentHistory> latest(long memberId, Consent terms) {
        return consents.findLatest(memberId, terms.getTermsId(), Limit.of(1)).stream().findFirst();
    }

    private static SettingResponse response(boolean pushEnabled, Optional<ConsentHistory> latest) {
        LocalDateTime agreedAt = latest.filter(h -> AGREED.equals(h.getConsentStatus()))
                .map(ConsentHistory::getRecordedAt).orElse(null);
        LocalDateTime revokedAt = latest.filter(h -> WITHDRAWN.equals(h.getConsentStatus()))
                .map(ConsentHistory::getRecordedAt).orElse(null);
        return new SettingResponse(pushEnabled, agreedAt, revokedAt);
    }
}
