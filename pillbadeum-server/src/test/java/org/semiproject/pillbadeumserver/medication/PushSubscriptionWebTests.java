package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.PushSubscriptionController;
import org.semiproject.pillbadeumserver.medication.service.PushSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PushSubscriptionController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class PushSubscriptionWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean PushSubscriptionService service;
    private static final String BODY = """
            {"endpoint":"https://fcm.googleapis.com/fcm/send/abc","keys":{"p256dh":"BKey","auth":"secret"},"userAgent":"Chrome"}
            """;

    @Test
    void pushRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/push/vapid-public-key")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void publicKeyIsReturnedAndMissingKeyIs503() throws Exception {
        when(service.publicKey()).thenReturn("BPublicKey");
        mvc.perform(get("/api/push/vapid-public-key").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicKey").value("BPublicKey"));

        when(service.publicKey()).thenThrow(new BusinessException(ErrorCode.PUSH_UNAVAILABLE, "키 없음"));
        mvc.perform(get("/api/push/vapid-public-key").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXTERNAL_API_FAILED"));
    }

    @Test
    void newBrowserIs201AndSameBrowserIs200() throws Exception {
        when(service.save(eq(7L), any())).thenReturn(new PushSubscriptionService.Saved(11, true),
                new PushSubscriptionService.Saved(11, false));
        mvc.perform(post("/api/push/subscriptions").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subscriptionId").value(11));
        mvc.perform(post("/api/push/subscriptions").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionId").value(11));
        verify(service, times(2)).save(eq(7L), argThat(body -> body.endpoint().endsWith("/abc")
                && body.keys().p256dh().equals("BKey") && body.keys().auth().equals("secret")));
    }

    @Test
    void missingKeysAreRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/push/subscriptions").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"https://fcm.googleapis.com/x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    private String bearer() { return "Bearer " + tokens.createAccessToken(7L, "test@example.test", "ROLE_USER"); }
}
