package org.semiproject.pillbadeumserver.medication.service;

import io.jsonwebtoken.Jwts;
import org.semiproject.pillbadeumserver.common.external.RestClientFactory;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;

/**
 * 브라우저 푸시 서비스로 알림 1건을 보낸다. 내용은 RFC 8291(aes128gcm)로 암호화하고 VAPID(RFC 8292) 서명을 붙인다.
 * 시간 제한 안에 끝나지 않거나 실패해도 재시도하지 않는다(COMMON-001). 구독 주소·키는 로그에 남기지 않는다.
 */
@Component
public class WebPushSender {
    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final int RECORD_SIZE = 4096;
    private static final Duration JWT_LIFETIME = Duration.ofHours(12);

    /** SENT: 푸시 서비스가 받음, GONE: 구독이 없어짐(404·410), FAILED: 그 밖의 실패. */
    public enum Result { SENT, GONE, FAILED }

    private final VapidKeys vapid;
    private final RestClient client;
    private final int ttlSeconds;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public WebPushSender(VapidKeys vapid,
                         @Value("${web-push.connect-timeout-ms:2000}") long connectTimeoutMs,
                         @Value("${web-push.read-timeout-ms:5000}") long readTimeoutMs,
                         @Value("${web-push.ttl-seconds:3600}") int ttlSeconds) {
        this(vapid, RestClientFactory.builder("", Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs))
                .build(), ttlSeconds, Clock.systemUTC());
    }

    WebPushSender(VapidKeys vapid, RestClient client, int ttlSeconds, Clock clock) {
        this.vapid = vapid;
        this.client = client;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    /** topic이 같은 알림은 푸시 서비스에 쌓여 있을 때 하나로 바뀐다. 영문·숫자·-·_ 32자 이하. */
    public Result send(PushSubscription subscription, byte[] payload, String topic) {
        try {
            URI endpoint = URI.create(subscription.getEndpoint());
            byte[] body = encrypt(payload, VapidKeys.publicKey(VapidKeys.decodeBase64Url(subscription.getP256dh())),
                    VapidKeys.decodeBase64Url(subscription.getAuth()), newKeyPair(), randomSalt());
            int status = client.post().uri(endpoint)
                    .header("Authorization", "vapid t=" + vapidToken(endpoint) + ", k=" + vapid.publicKeyText())
                    .header("TTL", Integer.toString(ttlSeconds))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Urgency", "high")
                    .header("Topic", topic)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
            if (status >= 200 && status < 300) return Result.SENT;
            if (status == 404 || status == 410) return Result.GONE;
            log.warn("웹 푸시 실패 subscriptionId={} status={}", subscription.getSubscriptionId(), status);
            return Result.FAILED;
        } catch (RuntimeException | GeneralSecurityException e) {
            log.warn("웹 푸시 실패 subscriptionId={} reason={}", subscription.getSubscriptionId(), e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    /** VAPID JWT. aud는 푸시 서비스의 출처(scheme://host)다. */
    String vapidToken(URI endpoint) {
        String audience = endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        return Jwts.builder()
                .header().type("JWT").and()
                .audience().single(audience) // 배열이 아닌 문자열로 보낸다(RFC 8292)
                .expiration(Date.from(clock.instant().plus(JWT_LIFETIME)))
                .subject(vapid.subject())
                .signWith(vapid.privateKey(), Jwts.SIG.ES256)
                .compact();
    }

    /**
     * RFC 8291 3.4·RFC 8188: salt(16) | rs(4) | idlen(1) | 보내는 쪽 공개 키(65) | 암호문.
     * 내용이 작아 레코드 하나로 보내며 끝 구분자 0x02를 붙인다.
     */
    static byte[] encrypt(byte[] payload, ECPublicKey receiverKey, byte[] authSecret, KeyPair senderKeys, byte[] salt)
            throws GeneralSecurityException {
        if (payload.length + 17 > RECORD_SIZE) throw new IllegalArgumentException("알림 내용이 너무 깁니다.");
        byte[] receiverRaw = VapidKeys.raw(receiverKey);
        byte[] senderRaw = VapidKeys.raw((ECPublicKey) senderKeys.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(senderKeys.getPrivate());
        agreement.doPhase(receiverKey, true);
        byte[] sharedSecret = agreement.generateSecret();

        byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, receiverRaw, senderRaw);
        byte[] ikm = hkdf(authSecret, sharedSecret, keyInfo, 32);
        byte[] cek = hkdf(salt, ikm, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 16);
        byte[] nonce = hkdf(salt, ikm, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(concat(payload, new byte[]{2}));

        byte[] header = ByteBuffer.allocate(16 + 4 + 1 + senderRaw.length)
                .put(salt).putInt(RECORD_SIZE).put((byte) senderRaw.length).put(senderRaw).array();
        return concat(header, encrypted);
    }

    /** HKDF-SHA256(RFC 5869). 필요한 길이가 32바이트 이하라 한 블록만 만든다. */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private byte[] randomSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
