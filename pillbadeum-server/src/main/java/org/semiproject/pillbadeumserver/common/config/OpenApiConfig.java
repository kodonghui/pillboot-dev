package org.semiproject.pillbadeumserver.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/* 설명. Swagger UI 설정 (http://localhost:8080/swagger-ui.html)
 *  오른쪽 위 'Authorize' 버튼에 로그인 응답의 accessToken 을 붙여넣으면
 *  이후 요청에 'Authorization: Bearer 토큰' 헤더가 붙는다. ('Bearer ' 는 직접 쓰지 않는다)
 *  컨트롤러에 @Tag(name = "프로필") 처럼 도메인 이름을 붙여 두면 화면에서 도메인별로 묶인다.
 * */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "필바듬 API", description = "복약 지도 챗봇 pillbadeum 서버 API", version = "v1"),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {
}
