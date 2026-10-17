package org.semiproject.pillbadeumserver.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.semiproject.pillbadeumserver.common.ErrorResponse;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/* 설명. 로그인은 했지만 권한(ROLE)이 모자란 주소를 요청했을 때 -> 403 Forbidden
 *  예) ROLE_USER 가 /api/admin/** 을 요청한 경우
 *  참고. '다른 회원의 데이터' 접근은 주소만으로 알 수 없으므로 서비스에서 SecurityUtil.checkOwner() 로 막는다.
 * */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCode.FORBIDDEN.getCode(), "이 요청을 처리할 권한이 없습니다.");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
