package org.semiproject.pillbadeumserver.medication.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class NotificationDtos {
    private NotificationDtos() {}

    /** 켜기는 동의가 살아 있지 않으면 notificationConsent가 필요하다. 끄기는 pushEnabled만 보낸다. */
    public record SettingRequest(@NotNull Boolean pushEnabled, @Valid ConsentAnswer notificationConsent) {}

    public record ConsentAnswer(@NotNull Boolean agreed, @Size(max = 10) String policyVersion) {}

    /** 켜기·끄기 공통 응답. 해당하지 않는 시각은 null이다. */
    public record SettingResponse(boolean pushEnabled, LocalDateTime consentAgreedAt, LocalDateTime consentRevokedAt) {}

    /** 설정 › 알림 화면용. consentPolicyVersion은 지금 동의받을 약관 버전이다. */
    public record SettingSummary(boolean pushEnabled, boolean consentActive, String consentPolicyVersion,
                                 long subscriptionCount) {}

    public record VapidKeyResponse(String publicKey) {}

    /** 브라우저 PushSubscription.toJSON() 모양 + 브라우저 정보. 키 형식과 허용 주소는 서비스에서 검사한다. */
    public record SubscriptionRequest(@NotBlank @Size(max = 2048) String endpoint,
                                      @NotNull @Valid SubscriptionKeys keys,
                                      String userAgent) {}

    public record SubscriptionKeys(@NotBlank @Size(max = 255) String p256dh, @NotBlank @Size(max = 255) String auth) {}

    public record SubscriptionResponse(long subscriptionId) {}
}
