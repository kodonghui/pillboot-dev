package org.semiproject.pillbadeumserver;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAccessDeniedHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAuthenticationEntryPoint;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.common.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 업무 API를 구현하지 않고 공개 경로와 공통 오류의 HTTP 계약을 검증한다. */
@WebMvcTest(controllers = AuthContractTests.ContractController.class)
@ActiveProfiles("test")
@Import({AuthContractTests.ContractController.class, SecurityConfig.class, JwtTokenProvider.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, CorsConfig.class,
        GlobalExceptionHandler.class})
class AuthContractTests {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;

    @Test
    void onlyTheFourDocumentedAuthMethodsArePublic() throws Exception {
        mvc.perform(get("/api/auth/consent-policies")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/email-availability")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/signup")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/consent-policies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/signup/extra")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/terms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }


    @Test
    void upstreamDetailsAreHiddenAndBothExternalFailuresUse503() throws Exception {
        String authorization = "Bearer " + tokens.createAccessToken(7L, "test@example.test", "ROLE_USER");
        mvc.perform(get("/api/contract/failed").header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(content().string(not(containsString("private_upstream_payload"))));
        mvc.perform(get("/api/contract/timeout").header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXTERNAL_TIMEOUT"));
    }

    @Test
    void oversizedUploadUses400AndFileTooLargeCode() throws Exception {
        String authorization = "Bearer " + tokens.createAccessToken(7L, "test@example.test", "ROLE_USER");
        mvc.perform(post("/api/contract/oversized").header("Authorization", authorization))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @RestController
    @TestComponent
    static class ContractController {
        @GetMapping({"/api/auth/consent-policies", "/api/auth/email-availability"})
        Map<String, Boolean> publicGet() { return Map.of("allowed", true); }

        @PostMapping({"/api/auth/signup", "/api/auth/login"})
        Map<String, Boolean> publicPost() { return Map.of("allowed", true); }


        @GetMapping("/api/contract/failed")
        void failed() {
            throw ExternalApiException.failed("test API", "private_upstream_payload",
                    new IllegalStateException("private_upstream_payload"));
        }

        @GetMapping("/api/contract/timeout")
        void timeout() { throw ExternalApiException.timeout("test API", null); }

        @PostMapping("/api/contract/oversized")
        void oversized() { throw new MaxUploadSizeExceededException(10_000_000); }
    }
}
