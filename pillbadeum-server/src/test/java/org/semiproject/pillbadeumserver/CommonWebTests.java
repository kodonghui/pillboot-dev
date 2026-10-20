package org.semiproject.pillbadeumserver;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 공통 보안 필터와 MVC 예외 처리기를 함께 검증합니다. 업무 API는 테스트에만 존재합니다. */
@WebMvcTest(controllers = CommonWebTests.ProbeController.class)
@ActiveProfiles("test")
@Import({CommonWebTests.ProbeController.class, SecurityConfig.class, JwtTokenProvider.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        CorsConfig.class, GlobalExceptionHandler.class,
        org.semiproject.pillbadeumserver.common.config.WebConfig.class})
class CommonWebTests {
    private static final String TEST_KEY = "cGlsbGJhZGV1bS10ZXN0LW9ubHktc2lnbmluZy1rZXktMzItYnl0ZXM";
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;

    private String token(String role) {
        return tokens.createAccessToken(7L, UUID.randomUUID() + "@example.test", role);
    }

    @Test
    void unauthenticatedRequestUsesCommonErrorAndDoesNotCreateSession() throws Exception {
        mvc.perform(get("/api/probe/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getRequest().getSession(false)).isNull());
    }

    @Test
    void bearerTokenAuthenticatesWithoutSession() throws Exception {
        mvc.perform(get("/api/probe/me").header("Authorization", "Bearer " + token("ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value(7))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getRequest().getSession(false)).isNull());
    }

    @Test
    void invalidAndExpiredTokensAreRejected() throws Exception {
        mvc.perform(get("/api/probe/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        String expired = Jwts.builder().subject("7").claim("loginId", "expired@example.test")
                .claim("role", "ROLE_USER").expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(TEST_KEY))).compact();
        mvc.perform(get("/api/probe/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void adminRouteChecksTokenRole() throws Exception {
        mvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + token("ROLE_USER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + token("ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void corsPreflightAllowsBearerHeaderAndExposesRequestId() throws Exception {
        mvc.perform(options("/api/probe/echo").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Request-Id")));
        mvc.perform(options("/api/probe/echo").header("Origin", "https://unlisted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void requestIdIsPreservedOrReplacedWithSafeValue() throws Exception {
        mvc.perform(get("/api/probe/me").header("X-Request-Id", "local-test_123"))
                .andExpect(header().string("X-Request-Id", "local-test_123"));
        mvc.perform(get("/api/probe/me").header("X-Request-Id", "invalid request id"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }

    @Test
    void tokenAuthenticatedPostDoesNotRequireSessionOrCsrfToken() throws Exception {
        mvc.perform(post("/api/probe/echo").header("Authorization", "Bearer " + token("ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"pill\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("pill"));
    }

    @Test
    void invalidJsonAndDtoHaveCommonBadRequestShape() throws Exception {
        String value = token("ROLE_USER");
        mvc.perform(post("/api/probe/echo").header("Authorization", "Bearer " + value)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/probe/echo").header("Authorization", "Bearer " + value)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("이름을 입력해 주세요."))
                .andExpect(jsonPath("$", aMapWithSize(2)));
    }

    @Test
    void dtoValidationReturnsConfiguredMessageWithoutRejectedInput() throws Exception {
        String rejected = "not-for-response";
        mvc.perform(post("/api/probe/echo").header("Authorization", "Bearer " + token("ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + rejected + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("이름은 10자까지 입력할 수 있어요."))
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(content().string(not(containsString(rejected))));
    }

    @Test
    void formValidationReturnsConfiguredDtoMessage() throws Exception {
        mvc.perform(post("/api/probe/form").header("Authorization", "Bearer " + token("ROLE_USER"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("이름을 입력해 주세요."))
                .andExpect(jsonPath("$", aMapWithSize(2)));
    }

    @Test
    void bindErrorsWithoutAFieldMessageUseCommonFallback() {
        for (boolean missingFieldError : new boolean[]{true, false}) {
            var binding = new BeanPropertyBindingResult(new Object(), "request");
            if (!missingFieldError) {
                binding.addError(new FieldError("request", "name", "not-for-response",
                        false, null, null, null));
            }
            var response = new GlobalExceptionHandler().handleBindException(new BindException(binding));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getBody().getMessage()).isEqualTo("입력값을 확인해주세요.");
        }
    }

    @Test
    void queryConstraintsUseInputErrorCode() throws Exception {
        mvc.perform(get("/api/probe/positive").param("count", "0")
                        .header("Authorization", "Bearer " + token("ROLE_USER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void businessAndDatabaseFailuresUseDocumentedErrorContract() throws Exception {
        String value = token("ROLE_USER");
        mvc.perform(get("/api/probe/not-found").header("Authorization", "Bearer " + value))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/probe/conflict").header("Authorization", "Bearer " + value))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("private_table"))));
        mvc.perform(get("/api/probe/timeout").header("Authorization", "Bearer " + value))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXTERNAL_TIMEOUT"));
    }

    @Test
    void knownConcurrentDrugDuplicateUsesConflictWithoutDatabaseDetails() throws Exception {
        mvc.perform(get("/api/probe/drug-duplicate").header("Authorization", "Bearer " + token("ROLE_USER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("이미 이 프로필에 등록된 약입니다."))
                .andExpect(content().string(not(containsString("uk_my_drug_profile_item"))))
                .andExpect(content().string(not(containsString("private_value"))));
    }

    @Test
    void otherUniqueAndForeignKeyFailuresRemainInternalErrors() throws Exception {
        for (String path : new String[]{"/api/probe/other-duplicate", "/api/probe/drug-foreign-key"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token("ROLE_USER")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(content().string(not(containsString("private_value"))));
        }
    }

    @Test
    void mvcErrorsPreserveStatusAndAllowHeader() throws Exception {
        String value = token("ROLE_USER");
        mvc.perform(get("/api/probe/missing-route").header("Authorization", "Bearer " + value))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/probe/echo").header("Authorization", "Bearer " + value))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")));
    }

    @Test
    void unexpectedErrorHidesInternalDetails() throws Exception {
        mvc.perform(get("/api/probe/failure").header("Authorization", "Bearer " + token("ROLE_USER")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("private_table"))));
    }

    @RestController
    @TestComponent
    static class ProbeController {
        record Body(@NotBlank(message = "이름을 입력해 주세요.")
                    @Size(max = 10, message = "이름은 10자까지 입력할 수 있어요.") String name) {}
        @GetMapping("/api/probe/me") Map<String, Long> me() {
            return Map.of("memberId", SecurityUtil.getCurrentMemberId());
        }
        @GetMapping("/api/admin/probe") Map<String, Boolean> admin() { return Map.of("allowed", true); }
        @PostMapping("/api/probe/echo") Body echo(@Valid @RequestBody Body body) { return body; }
        @PostMapping("/api/probe/form") Body form(@Valid @ModelAttribute Body body) { return body; }
        @GetMapping("/api/probe/positive") int positive(@RequestParam @Min(1) int count) { return count; }
        @GetMapping("/api/probe/not-found") void notFound() { throw new NotFoundException(); }
        @GetMapping("/api/probe/conflict") void conflict() {
            throw new DataIntegrityViolationException("private_table constraint");
        }
        @GetMapping("/api/probe/drug-duplicate") void drugDuplicate() {
            throw databaseFailure("`my_drug`.`uk_my_drug_profile_item`", 1062);
        }
        @GetMapping("/api/probe/other-duplicate") void otherDuplicate() {
            throw databaseFailure("other_unique", 1062);
        }
        @GetMapping("/api/probe/drug-foreign-key") void drugForeignKey() {
            throw databaseFailure("uk_my_drug_profile_item", 1452);
        }
        private DataIntegrityViolationException databaseFailure(String constraint, int mysqlCode) {
            return new DataIntegrityViolationException("private_value", new org.hibernate.exception.ConstraintViolationException(
                    "private_value", new java.sql.SQLIntegrityConstraintViolationException("private_value", "23000", mysqlCode), constraint));
        }
        @GetMapping("/api/probe/timeout") void timeout() { throw ExternalApiException.timeout("test API", null); }
        @GetMapping("/api/probe/failure") void failure() { throw new IllegalStateException("private_table SQL"); }
    }
}
