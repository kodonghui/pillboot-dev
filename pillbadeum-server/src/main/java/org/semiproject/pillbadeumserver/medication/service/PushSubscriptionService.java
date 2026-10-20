package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscriptionRepository;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SubscriptionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 브라우저 웹 푸시 구독 저장(NOTI-001 4·5단계). 같은 endpoint는 1행만 두고, 다른 회원 것이었으면 지금 회원으로 옮긴다.
 * 서버가 이 주소로 직접 요청을 보내므로 허용한 브라우저 푸시 서비스 주소만 받는다.
 */
@Service
@Transactional(readOnly = true)
public class PushSubscriptionService {
    static final int MAX_USER_AGENT = 500;

    private final PushSubscriptionRepository subscriptions;
    private final VapidKeys vapid;
    private final List<String> allowedHosts;
    private final Clock clock;

    @Autowired
    public PushSubscriptionService(PushSubscriptionRepository subscriptions, VapidKeys vapid,
                                   @Value("${web-push.allowed-hosts}") String allowedHosts) {
        this(subscriptions, vapid, allowedHosts, Clock.system(TimeZoneConfig.ZONE));
    }

    PushSubscriptionService(PushSubscriptionRepository subscriptions, VapidKeys vapid, String allowedHosts, Clock clock) {
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(String::strip)
                .filter(host -> !host.isEmpty()).map(host -> host.toLowerCase(Locale.ROOT)).toList();
        this.clock = clock;
    }

    public String publicKey() {
        return vapid.publicKeyText();
    }

    /** 저장 결과. created가 true면 이 브라우저를 처음 저장한 것이다. */
    public record Saved(long subscriptionId, boolean created) {}

    @Transactional
    public Saved save(long memberId, SubscriptionRequest request) {
        vapid.publicKeyText(); // 키가 없으면 보낼 수 없으므로 저장하지 않는다.
        String endpoint = request.endpoint().strip();
        requireAllowedEndpoint(endpoint);
        String p256dh = request.keys().p256dh().strip();
        String auth = request.keys().auth().strip();
        requireKeys(p256dh, auth);
        String userAgent = request.userAgent() == null ? null
                : request.userAgent().substring(0, Math.min(MAX_USER_AGENT, request.userAgent().length()));

        boolean existed = subscriptions.findByEndpoint(endpoint).isPresent();
        // endpoint UNIQUE에 기대 한 문장으로 넣거나 고친다. 같은 브라우저를 동시에 보내도 1행만 남는다.
        subscriptions.upsert(memberId, endpoint, p256dh, auth, userAgent,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        long id = subscriptions.findByEndpoint(endpoint).map(PushSubscription::getSubscriptionId)
                .orElseThrow(() -> new IllegalStateException("저장한 푸시 구독을 찾지 못했습니다."));
        return new Saved(id, !existed);
    }

    private void requireAllowedEndpoint(String endpoint) {
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(endpoint)) throw invalid();
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowedHost = allowedHosts.stream().anyMatch(allowed -> allowed.startsWith(".")
                ? host.endsWith(allowed) && host.length() > allowed.length()
                : host.equals(allowed));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || !allowedHost) {
            throw invalid();
        }
    }

    /** 브라우저 키: p256dh는 65바이트 P-256 공개 키, auth는 16바이트 비밀값(RFC 8291). */
    private static void requireKeys(String p256dh, String auth) {
        try {
            VapidKeys.publicKey(VapidKeys.decodeBase64Url(p256dh));
            if (VapidKeys.decodeBase64Url(auth).length != 16) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "브라우저 구독 키를 확인해주세요.");
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT, "지원하는 브라우저 푸시 주소가 아닙니다.");
    }
}
