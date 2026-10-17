package org.semiproject.pillbadeumserver.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.semiproject.pillbadeumserver.common.ErrorResponse;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/* 설명. 로그인이 필요한 API 를 인증 없이 요청했을 때 -> 401 Unauthorized (AUTH-003 비로그인 접근 막기)
 *  ----------------------------------------------------------------------------------
 *  보안 필터에서 생긴 거절은 GlobalExceptionHandler 까지 가지 않으므로, ErrorResponse 를 여기서 직접 JSON 으로 쓴다.
 *  프론트엔드는 401 을 받으면 저장된 토큰을 지우고 로그인 화면으로 보내면 된다.
 *  - 401 : 누구인지 모른다 (토큰 없음, 만료, 위조)                    -> 이 클래스
 *  - 403 : 누구인지는 아는데 권한이 없다                               -> JwtAccessDeniedHandler
 * */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        Object jwtError = request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE);
        String detail = jwtError != null ? jwtError.toString() : "로그인이 필요합니다.";

        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.UNAUTHORIZED.getCode(), detail);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
