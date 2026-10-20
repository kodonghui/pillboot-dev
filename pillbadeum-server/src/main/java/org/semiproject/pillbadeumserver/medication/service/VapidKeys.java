package org.semiproject.pillbadeumserver.medication.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 웹 푸시 서버 키 한 쌍(VAPID, P-256). 공개 키만 API로 내주고 개인 키는 환경 변수에만 둔다(COMMON-004).
 * 세 값이 모두 비어 있으면 서버는 뜨고 푸시 기능만 503이다. 일부만 있거나 키가 짝이 아니면 시작하지 않는다.
 * 키를 바꾸면 기존 브라우저 구독을 모두 다시 받아야 한다.
 */
@Component
public class VapidKeys {
    static final ECParameterSpec P256 = p256();

    private final String publicKeyText;
    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String subject;

    public VapidKeys(@Value("${web-push.vapid.public-key:}") String publicKey,
                     @Value("${web-push.vapid.private-key:}") String privateKey,
                     @Value("${web-push.vapid.subject:}") String subject) {
        boolean anySet = !publicKey.isBlank() || !privateKey.isBlank() || !subject.isBlank();
        if (!anySet) {
            this.publicKeyText = null;
            this.publicKey = null;
            this.privateKey = null;
            this.subject = null;
            return;
        }
        if (publicKey.isBlank() || privateKey.isBlank() || subject.isBlank()) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, VAPID_SUBJECT는 함께 설정해야 합니다.");
        }
        if (!subject.startsWith("mailto:") && !subject.startsWith("https://")) {
            throw new IllegalStateException("VAPID_SUBJECT는 mailto: 또는 https:// 로 시작해야 합니다.");
        }
        this.publicKeyText = publicKey.strip();
        this.publicKey = publicKey(decode(this.publicKeyText, "VAPID_PUBLIC_KEY"));
        this.privateKey = privateKey(decode(privateKey.strip(), "VAPID_PRIVATE_KEY"));
        this.subject = subject.strip();
        requirePair(this.publicKey, this.privateKey);
    }

    public boolean configured() { return publicKey != null; }

    /** 브라우저가 구독을 만들 때 쓰는 공개 키(base64url, 65바이트 비압축 점). */
    public String publicKeyText() { return require(publicKeyText); }
    public ECPublicKey publicKey() { return require(publicKey); }
    public ECPrivateKey privateKey() { return require(privateKey); }
    public String subject() { return require(subject); }

    private <T> T require(T value) {
        if (value == null) throw new BusinessException(ErrorCode.PUSH_UNAVAILABLE, "웹 푸시 키가 설정되지 않았습니다.");
        return value;
    }

    /** 브라우저가 주는 65바이트 비압축 공개 키(0x04 || X || Y)를 Java 키로 바꾼다. */
    static ECPublicKey publicKey(byte[] raw) {
        if (raw.length != 65 || raw[0] != 0x04) throw new IllegalArgumentException("P-256 공개 키가 아닙니다.");
        try {
            ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                    new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("P-256 공개 키가 아닙니다.", e);
        }
    }

    /** Java 공개 키를 65바이트 비압축 형식으로 바꾼다. */
    static byte[] raw(ECPublicKey key) {
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        copyFixed(key.getW().getAffineX(), raw, 1);
        copyFixed(key.getW().getAffineY(), raw, 33);
        return raw;
    }

    static byte[] decodeBase64Url(String value) {
        return Base64.getUrlDecoder().decode(value.replace("=", ""));
    }

    private static byte[] decode(String value, String name) {
        try {
            return decodeBase64Url(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + "는 base64url 형식이어야 합니다.");
        }
    }

    private static ECPrivateKey privateKey(byte[] raw) {
        if (raw.length != 32) throw new IllegalStateException("VAPID_PRIVATE_KEY는 32바이트 P-256 개인 키여야 합니다.");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID_PRIVATE_KEY를 읽을 수 없습니다.", e);
        }
    }

    /** 개인 키로 서명한 값을 공개 키로 확인해 두 키가 짝인지 본다. */
    private static void requirePair(ECPublicKey publicKey, ECPrivateKey privateKey) {
        try {
            byte[] probe = "pillbadeum-vapid-check".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            if (!verifier.verify(signature)) throw new IllegalStateException("VAPID 공개 키와 개인 키가 짝이 아닙니다.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID 키를 확인할 수 없습니다.", e);
        }
    }

    private static void copyFixed(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
