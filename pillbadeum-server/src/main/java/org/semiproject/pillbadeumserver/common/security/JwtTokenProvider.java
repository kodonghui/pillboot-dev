package org.semiproject.pillbadeumserver.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/* 설명. Access Token 을 발급하고 검증하는 부품 (AUTH-003)
 *  ----------------------------------------------------------------------------------
 *  토큰에 담는 값 (Payload)
 *  - sub     : 회원 PK
 *  - loginId : 회원 email에서 전달한 인증 식별자 (별도 login_id 컬럼을 요구하지 않음)
 *  - role    : 권한 ('ROLE_USER' 등)
 *  - iat, exp: 발급 시각, 만료 시각
 *  주의. 페이로드는 암호화되지 않는다. 누구나 디코딩해서 읽을 수 있으므로 이름 · 연락처 · 복용 약 같은 정보는 넣지 않는다.
 *  ----------------------------------------------------------------------------------
 *  로그인 흐름 (회원 담당 MemberService 에서)
 *  1) email 로 회원을 찾고 passwordEncoder.matches(입력 비밀번호, 저장된 해시)로 비밀번호를 확인한다.
 *  2) 회원이 없거나 비밀번호가 틀리면 BusinessException(ErrorCode.LOGIN_FAILED, "이메일 또는 비밀번호가 맞지 않습니다.")를 던진다.
 *  3) 회원의 member_id, email, user_role 값을 createAccessToken(memberId, email, userRole)에 전달한다.
 * */
@Component
public class JwtTokenProvider {

    private static final String BEARER_PREFIX = "Bearer ";

    // 서명과 검증에 쓰는 비밀키 (서버만 안다)
    private final SecretKey secretKey;
    // Access Token 유효시간 (밀리초)
    private final long accessTokenExpiration;

    /* 설명. jwt.secret 은 Base64URL 로 인코딩한 256비트(32바이트) 이상의 문자열이어야 한다.
     *  짧으면 Keys.hmacShaKeyFor() 가 WeakKeyException 을 던져 서버가 뜨지 않는다.
     *  값은 .env 의 JWT_SECRET 에 넣고, Git 에는 올리지 않는다. (COMMON-004)
     * */
    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.access-token-expiration}") long accessTokenExpiration) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT_SECRET을 개인 .env 또는 환경변수에 설정해주세요. README의 키 생성 방법을 확인하세요.");
        }
        if (accessTokenExpiration <= 0) {
            throw new IllegalArgumentException("JWT access-token-expiration은 양수여야 합니다.");
        }
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret));
        this.accessTokenExpiration = accessTokenExpiration;
    }

    /* 목차. 1. Access Token 발급 (로그인 성공 시 MemberService 가 부른다) */
    public String createAccessToken(Long memberId, String loginId, String role) {

        Date now = new Date();

        return Jwts.builder()
                .header().type("JWT").and()
                .subject(String.valueOf(memberId))
                .claim("loginId", loginId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    /* 목차. 2. Access Token 검증 (요청마다 JwtAuthenticationFilter 가 부른다)
     *  서명 · 만료를 확인하고, 통과하면 토큰 안의 값으로 사용자 객체를 만든다. (DB 조회 없음)
     *  실패하면 JwtException(만료는 ExpiredJwtException) 을 던진다.
     * */
    public CustomUserDetails parseAccessToken(String token) {

        Claims claims = parseClaims(token);

        return new CustomUserDetails(
                Long.parseLong(claims.getSubject()),
                claims.get("loginId", String.class),
                claims.get("role", String.class)
        );
    }

    /* 목차. 3. 요청의 Authorization 헤더에서 토큰만 꺼낸다. 없으면 null */
    public String resolveToken(HttpServletRequest request) {

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
