package org.semiproject.pillbadeumserver.common.security;

/**
 * V3-KAKAO-013~015: {@code /api/kakao-bridge/**}를 부른 카카오 다리. 회원이 아니며 카카오 사용자 키의 HMAC만 갖는다.
 * toString에 HMAC을 넣지 않는다(로그에 남기지 않음).
 */
public record KakaoBridgePrincipal(String kakaoUserHmac) {

    @Override
    public String toString() {
        return "KakaoBridgePrincipal{}";
    }
}
