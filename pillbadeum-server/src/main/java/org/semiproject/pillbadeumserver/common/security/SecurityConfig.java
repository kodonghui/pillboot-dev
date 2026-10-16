package org.semiproject.pillbadeumserver.common.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/* 설명. Spring Security 설정 (JWT + REST API)
 *  ----------------------------------------------------------------------------------
 *  주소 규칙 작성 방법
 *  - 위에서부터 차례로 검사하고, 처음 맞는 규칙 하나만 적용한다. (넓은 규칙은 맨 아래)
 *  - 기본값은 맨 아래의 anyRequest().authenticated() 이다. 즉 '로그인한 회원만'이 기본이다.
 *    그래서 대부분의 API 는 여기에 규칙을 추가하지 않아도 된다.
 *  - 토큰 없이 열어야 하는 API 가 생겼을 때만 자기 도메인 블록에 permitAll() 한 줄을 추가한다.
 *    (블록을 나눠 두면 여러 명이 동시에 고쳐도 Git 충돌이 적다)
 *  ----------------------------------------------------------------------------------
 *  주의. '로그인했는가'만 여기서 막는다. '내 데이터인가'는 서비스에서 SecurityUtil.checkOwner() 로 막는다.
 * */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklist tokenBlacklist;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider,
                          TokenBlacklist tokenBlacklist,
                          JwtAuthenticationEntryPoint authenticationEntryPoint,
                          JwtAccessDeniedHandler accessDeniedHandler) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklist = tokenBlacklist;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${springdoc.api-docs.enabled:false}") boolean docsEnabled) throws Exception {

        http
                /* 목차. 1. 세션 · 기본 로그인 화면 · Basic 인증 · CSRF 끄기 (토큰 방식이므로 필요 없다) */
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)

                /* 목차. 2. CORS (CorsConfig 의 corsConfigurationSource 빈을 쓴다) */
                .cors(Customizer.withDefaults())

                /* 목차. 3. 주소별 접근 규칙 */
                .authorizeHttpRequests(auth -> {
                    if (docsEnabled) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    }
                    auth

                        /* 목차. 3-1. 공통 */
                        // SSE(챗봇 답변 스트리밍)가 끝날 때의 내부 처리. 첫 요청에서 이미 검사를 통과했다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health").permitAll()

                        /* 목차. 3-2. 회원 (AUTH) */
                        // 회원가입 약관 · 동의 항목 조회 (AUTH-001)
                        .requestMatchers(HttpMethod.GET, "/api/auth/terms", "/api/auth/terms/**").permitAll()
                        // 회원가입 (AUTH-002)
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup").permitAll()
                        // 로그인 (AUTH-003)
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // 로그아웃(AUTH-004)은 토큰이 있어야 하므로 기본 규칙(authenticated)을 따른다.

                        /* 목차. 3-3. 프로필 (PROF)        /api/profiles/** */

                        /* 목차. 3-4. 약 등록 · 이미지 (REG) /api/images/**, /api/drugs/search 등 */

                        /* 목차. 3-5. 내 약 목록 (DRUG)     /api/medications/** */

                        /* 목차. 3-6. 복약 일정 · 기록 (SCHED, DOSE) /api/schedules/**, /api/doses/** */

                        /* 목차. 3-7. 복약 알림 (NOTI)      /api/notifications/** */

                        /* 목차. 3-8. AI 챗봇 (CHAT)       /api/chats/** */

                        /* 목차. 3-9. 나머지 (맨 아래에 둔다) */
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated();
                })

                /* 목차. 4. 인증(401) · 인가(403) 실패 응답을 ErrorResponse 모양으로 */
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                /* 목차. 5. JWT 필터 등록 */
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklist),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /* 목차. 6. 비밀번호 암호화 (BCrypt)
     *  회원가입 : passwordEncoder.encode(rawPassword)
     *  로그인   : passwordEncoder.matches(rawPassword, savedHash)
     * */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
