package org.semiproject.pillbadeumserver.medication.service;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.external.RestClientFactory;
import org.semiproject.pillbadeumserver.medication.domain.PushSubscription;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/** 실제 푸시 서비스 대신 로컬 테스트 서버로만 보낸다. */
class WebPushSenderTests {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void 암호화는_RFC_8291_예시와_같은_결과를_만든다() throws Exception {
        // RFC 8291 5장 예시 값
        ECPublicKey receiver = VapidKeys.publicKey(b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"));
        KeyPair sender = new KeyPair(
                VapidKeys.publicKey(b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                privateKey(b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));

        byte[] body = WebPushSender.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                receiver, b64("BTBZMqHH6r4Tts7J_aSIgg"), sender, b64("DGv6ra1nlYgDCS1FRnbzlw"));

        assertThat(PushTestKeys.encode(body)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_"
                        + "yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void 받는_브라우저의_개인_키로_풀면_원래_내용이_나온다() throws Exception {
        KeyPair browser = PushTestKeys.generate();
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 7);
        byte[] payload = "{\"title\":\"필받음 · 복약 시간이에요\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPushSender.encrypt(payload, (ECPublicKey) browser.getPublic(), auth, PushTestKeys.generate(),
                new byte[16]);

        assertThat(decrypt(body, browser, auth)).isEqualTo(payload);
    }

    @Test
    void 성공_구독없어짐_실패를_구분하고_VAPID_서명과_헤더를_붙인다() throws Exception {
        Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (var route : Map.of("/ok", 201, "/gone", 410, "/missing", 404, "/busy", 429, "/down", 503).entrySet()) {
            server.createContext(route.getKey(), exchange -> {
                seen.put(route.getKey(), Map.of(
                        "authorization", exchange.getRequestHeaders().getFirst("Authorization"),
                        "ttl", exchange.getRequestHeaders().getFirst("TTL"),
                        "encoding", exchange.getRequestHeaders().getFirst("Content-Encoding"),
                        "topic", exchange.getRequestHeaders().getFirst("Topic")));
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(route.getValue(), -1);
                exchange.close();
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        VapidKeys vapid = PushTestKeys.vapid();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T22:00:00Z"), ZoneOffset.UTC);
        WebPushSender sender = new WebPushSender(vapid,
                RestClientFactory.builder("", Duration.ofSeconds(2), Duration.ofSeconds(5)).build(), 3600, clock);
        KeyPair browser = PushTestKeys.generate();

        assertThat(sender.send(subscription(base + "/ok", browser), new byte[]{1}, "dose-20261019-0700"))
                .isEqualTo(WebPushSender.Result.SENT);
        assertThat(sender.send(subscription(base + "/gone", browser), new byte[]{1}, "t")).isEqualTo(WebPushSender.Result.GONE);
        assertThat(sender.send(subscription(base + "/missing", browser), new byte[]{1}, "t")).isEqualTo(WebPushSender.Result.GONE);
        assertThat(sender.send(subscription(base + "/busy", browser), new byte[]{1}, "t")).isEqualTo(WebPushSender.Result.FAILED);
        assertThat(sender.send(subscription(base + "/down", browser), new byte[]{1}, "t")).isEqualTo(WebPushSender.Result.FAILED);
        assertThat(sender.send(subscription("http://127.0.0.1:1/closed", browser), new byte[]{1}, "t"))
                .isEqualTo(WebPushSender.Result.FAILED);

        Map<String, String> headers = seen.get("/ok");
        assertThat(headers.get("ttl")).isEqualTo("3600");
        assertThat(headers.get("encoding")).isEqualTo("aes128gcm");
        assertThat(headers.get("topic")).isEqualTo("dose-20261019-0700");
        String authorization = headers.get("authorization");
        assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + vapid.publicKeyText());
        String token = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        Claims claims = Jwts.parser().verifyWith(vapid.publicKey()).clock(() -> java.util.Date.from(clock.instant()))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.getAudience()).containsExactly(base);
        assertThat(claims.getSubject()).isEqualTo("mailto:team@example.com");
        assertThat(claims.getExpiration().toInstant()).isEqualTo(clock.instant().plus(Duration.ofHours(12)));
    }

    @Test
    void VAPID_대상은_푸시_서비스_출처만_쓴다() {
        WebPushSender sender = new WebPushSender(PushTestKeys.vapid(), null, 3600, Clock.systemUTC());
        String token = sender.vapidToken(URI.create("https://fcm.googleapis.com/fcm/send/abc?x=1"));
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"aud\":\"https://fcm.googleapis.com\"");
    }

    private static PushSubscription subscription(String endpoint, KeyPair browser) {
        PushSubscription row = BeanUtils.instantiateClass(PushSubscription.class);
        ReflectionTestUtils.setField(row, "subscriptionId", 1L);
        ReflectionTestUtils.setField(row, "endpoint", endpoint);
        ReflectionTestUtils.setField(row, "p256dh", PushTestKeys.publicText(browser));
        ReflectionTestUtils.setField(row, "auth", PushTestKeys.encode(new byte[16]));
        return row;
    }

    /** 브라우저 쪽 복호화(RFC 8291 3.4 역순). */
    private static byte[] decrypt(byte[] body, KeyPair browser, byte[] auth) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        buffer.get(salt);
        assertThat(buffer.getInt()).isEqualTo(4096);
        byte[] senderRaw = new byte[buffer.get()];
        buffer.get(senderRaw);
        byte[] cipherText = new byte[buffer.remaining()];
        buffer.get(cipherText);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browser.getPrivate());
        agreement.doPhase(VapidKeys.publicKey(senderRaw), true);
        byte[] browserRaw = VapidKeys.raw((ECPublicKey) browser.getPublic());
        byte[] keyInfo = join("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, browserRaw, senderRaw);
        byte[] ikm = WebPushSender.hkdf(auth, agreement.generateSecret(), keyInfo, 32);
        byte[] cek = WebPushSender.hkdf(salt, ikm, join("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 16);
        byte[] nonce = WebPushSender.hkdf(salt, ikm, join("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(cipherText);
        assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(plain, plain.length - 1);
    }

    private static ECPrivateKey privateKey(byte[] raw) throws Exception {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), VapidKeys.P256));
    }

    private static byte[] b64(String value) {
        return VapidKeys.decodeBase64Url(value);
    }

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
