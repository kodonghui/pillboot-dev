package org.semiproject.pillbadeumserver.kakao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * V3-KAKAO-012·013: 카카오톡 연결 코드의 글자·모양·해시.
 * 코드는 헷갈리는 0·O·1·I를 뺀 32자에서 8자를 뽑고 숫자를 하나 이상 넣어 {@code XXXX-XXXX}로 보인다.
 * 서버는 하이픈 없는 대문자 8자의 SHA-256만 저장하고 원문은 만든 응답에서 한 번만 준다.
 */
public final class KakaoLinkCodes {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern CODE = Pattern.compile("[A-HJ-NP-Z2-9]{8}");
    private static final Pattern RECEIVED = Pattern.compile("([A-Za-z0-9]{4})[- ]?([A-Za-z0-9]{4})");
    private static final SecureRandom RANDOM = new SecureRandom();

    private KakaoLinkCodes() {
    }

    /** 숫자가 하나 이상 든 새 코드 8자(하이픈 없음). */
    public static String generate() {
        while (true) {
            char[] code = new char[8];
            boolean digit = false;
            for (int i = 0; i < code.length; i++) {
                code[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
                digit |= Character.isDigit(code[i]);
            }
            if (digit) {
                return new String(code);
            }
        }
    }

    /** 화면에 보이는 모양 {@code 7K3Q-M9XA}. */
    public static String display(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /**
     * 카톡에서 받은 글을 저장 모양(대문자 8자)으로 바꾼다. 앞뒤 공백, 가운데 하이픈 또는 공백 하나만 받는다.
     * 코드 글자가 아니면 빈 값이다(그 글은 틀린 코드로 셈).
     */
    public static Optional<String> normalize(String received) {
        if (received == null) {
            return Optional.empty();
        }
        var matcher = RECEIVED.matcher(received.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String code = (matcher.group(1) + matcher.group(2)).toUpperCase(Locale.ROOT);
        return CODE.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }

    /** 저장 모양 코드의 SHA-256 16진값(소문자 64자). */
    public static String hash(String code) {
        return sha256Hex(code);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
