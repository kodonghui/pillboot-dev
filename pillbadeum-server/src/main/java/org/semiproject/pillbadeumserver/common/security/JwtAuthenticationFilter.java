package org.semiproject.pillbadeumserver.common.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/* 설명. 요청마다 Authorization 헤더의 Bearer 토큰을 확인해서 '누구의 요청인지'를 Spring Security 에 알려주는 필터
 *  ----------------------------------------------------------------------------------
 *  1) 토큰이 없으면 : 아무것도 하지 않고 넘긴다. (비로그인 요청)
 *  2) 토큰이 유효하면 : SecurityContext 에 인증 정보를 넣는다.
 *  3) 토큰이 잘못됐으면(만료 · 위조) : 인증 정보를 넣지 않고, 이유만 요청에 적어 둔다.
 *  ----------------------------------------------------------------------------------
 *  이 필터는 요청을 막지 않는다. 막을지 말지는 SecurityConfig 의 주소 규칙이 정하고,
 *  막힌 경우의 401 응답은 JwtAuthenticationEntryPoint 가 여기서 적어 둔 이유를 읽어서 만든다.
 *  주의. @Component 로 등록하면 서블릿 필터로 한 번 더 등록되어 두 번 실행되므로 SecurityConfig 에서 new 로 만든다.
 * */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    // 토큰이 잘못된 이유를 요청에 적어둘 때 쓰는 이름. JwtAuthenticationEntryPoint 가 이 이름으로 읽는다.
    public static final String JWT_ERROR_ATTRIBUTE = "jwtError";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = jwtTokenProvider.resolveToken(request);

        if (token != null) {
            try {
                CustomUserDetails user = jwtTokenProvider.parseAccessToken(token);

                UsernamePasswordAuthenticationToken authentication =
                        UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);

            } catch (ExpiredJwtException e) {
                request.setAttribute(JWT_ERROR_ATTRIBUTE, "로그인 시간이 만료되었어요. 다시 로그인해 주세요.");

            } catch (JwtException | IllegalArgumentException e) {
                // 서명 불일치(위조), 형식 오류 등. 토큰 값은 로그에 남기지 않는다.
                log.debug("[JWT] 잘못된 토큰 : {} {}", request.getMethod(), request.getRequestURI());
                request.setAttribute(JWT_ERROR_ATTRIBUTE, "유효하지 않은 토큰입니다.");
            }
        }

        filterChain.doFilter(request, response);
    }
}
