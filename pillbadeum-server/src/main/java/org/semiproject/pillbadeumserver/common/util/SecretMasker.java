package org.semiproject.pillbadeumserver.common.util;

import java.util.regex.Pattern;

/* 설명. 로그에 남기기 전에 비밀값(서비스 키, API 키, 토큰, 비밀번호)을 가려 주는 도구 (COMMON-004)
 *  ----------------------------------------------------------------------------------
 *  외부 API 호출이 실패하면 예외 메시지에 요청 주소가 그대로 들어 있다.
 *      예) I/O error on GET request for "https://apis.data.go.kr/...?serviceKey=abc123..."
 *  이걸 그대로 찍으면 로그 파일이나 화면 캡처로 서비스 키가 새어 나간다.
 *  ----------------------------------------------------------------------------------
 *  사용 예) log.warn("실패: {}", SecretMasker.mask(e.getMessage()));
 * */
public final class SecretMasker {

    // 쿼리 문자열 · JSON 안의 key=value, "key":"value" 모양
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(service_?key|api_?key|access_?token|token|password|secret)(\"?\\s*[=:]\\s*\"?)([^&\\s\"',}]+)");
    // Authorization: Bearer xxxxx
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    // OpenAI API 키 모양 (sk-...)
    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    private static final String MASK = "****";

    private SecretMasker() {
    }

    public static String mask(String text) {

        if (text == null) {
            return null;
        }

        String masked = KEY_VALUE.matcher(text).replaceAll("$1$2" + MASK);
        masked = BEARER.matcher(masked).replaceAll("$1" + MASK);
        return OPENAI_KEY.matcher(masked).replaceAll(MASK);
    }
}
