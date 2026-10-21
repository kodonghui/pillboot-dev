package org.semiproject.pillbadeumserver.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/* 설명. CORS 설정
 *  프론트엔드(예: http://localhost:5173)와 백엔드(http://localhost:8080)의 오리진이 다르므로
 *  "이 오리진의 요청은 허용한다"고 브라우저에게 알려 줘야 JavaScript 가 응답을 읽을 수 있다.
 *  ----------------------------------------------------------------------------------
 *  허용할 오리진은 application.yaml 의 app.cors.allowed-origins (쉼표로 여러 개) 에서 바꾼다.
 *  SecurityConfig 의 .cors(Customizer.withDefaults()) 가 이 빈을 찾아서 쓴다.
 *  (보안 필터보다 먼저 처리되어야 사전 확인 요청(OPTIONS)이 401 로 막히지 않는다)
 * */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            CorsProperties properties) {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Location", "X-Request-Id", "X-Pill-Identification-Version"));
        configuration.setAllowCredentials(false);
        // 사전 확인(OPTIONS) 결과를 브라우저가 1시간 동안 기억한다.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
