package org.semiproject.pillbadeumserver.common.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* 설명. 로그아웃한 토큰을 만료 시각까지 기억해 두는 목록 (AUTH-004)
 *  ----------------------------------------------------------------------------------
 *  JWT 는 서버가 따로 기억하지 않으므로, 프론트에서 토큰을 지워도 누가 복사해 둔 토큰은 만료 전까지 쓸 수 있다.
 *  그래서 로그아웃한 토큰을 여기 넣어 두고, JwtAuthenticationFilter 가 이 목록에 있는 토큰은 거절한다.
 *  ----------------------------------------------------------------------------------
 *  로그아웃 API (회원 담당) 예시
 *      String token = jwtTokenProvider.resolveToken(request);
 *      tokenBlacklist.add(token, jwtTokenProvider.getExpiration(token));
 *  ----------------------------------------------------------------------------------
 *  주의. 메모리에 저장하므로 서버를 다시 켜면 목록이 비워진다. (팀 프로젝트 규모에서는 충분하다)
 *       서버를 여러 대 띄우게 되면 Redis 같은 공용 저장소로 옮겨야 한다.
 * */
@Component
public class TokenBlacklist {

    // 토큰 -> 만료 시각(밀리초)
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public void add(String token, Date expiration) {
        if (token != null && expiration != null) {
            tokens.put(token, expiration.getTime());
        }
    }

    public boolean contains(String token) {
        return tokens.containsKey(token);
    }

    /* 설명. 이미 만료된 토큰은 어차피 검증에서 걸리므로 10분마다 목록에서 지운다. */
    @Scheduled(fixedDelay = 600_000)
    public void removeExpired() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(expiresAt -> expiresAt < now);
    }
}
