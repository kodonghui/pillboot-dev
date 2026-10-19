package org.semiproject.pillbadeumserver.medication.service;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** 테스트마다 새로 만드는 P-256 키. 실제 서버 키나 브라우저 키는 쓰지 않는다. */
final class PushTestKeys {
    private PushTestKeys() {}

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String publicText(KeyPair pair) {
        return encode(VapidKeys.raw((ECPublicKey) pair.getPublic()));
    }

    static String privateText(KeyPair pair) {
        BigInteger d = ((ECPrivateKey) pair.getPrivate()).getS();
        byte[] bytes = d.toByteArray();
        byte[] fixed = new byte[32];
        int start = Math.max(0, bytes.length - 32);
        System.arraycopy(bytes, start, fixed, 32 - (bytes.length - start), bytes.length - start);
        return encode(fixed);
    }

    static VapidKeys vapid() {
        KeyPair pair = generate();
        return new VapidKeys(publicText(pair), privateText(pair), "mailto:team@example.com");
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
