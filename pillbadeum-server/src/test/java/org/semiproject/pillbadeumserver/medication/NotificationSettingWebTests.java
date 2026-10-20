package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.NotificationSettingController;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingResponse;
import org.semiproject.pillbadeumserver.medication.dto.NotificationDtos.SettingSummary;
import org.semiproject.pillbadeumserver.medication.service.NotificationSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationSettingController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class NotificationSettingWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean NotificationSettingService service;
    private static final String PATH = "/api/notification-settings";
    private static final String TURN_ON = """
            {"pushEnabled":true,"notificationConsent":{"agreed":true,"policyVersion":"1.0"}}
            """;

    @Test
    void settingRoutesRequireAuthentication() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(TURN_ON)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void putUsesMemberFromTokenAndReturnsConsentTimes() throws Exception {
        when(service.change(eq(7L), any())).thenReturn(new SettingResponse(true, LocalDateTime.of(2026, 10, 19, 8, 30), null));
        mvc.perform(put(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(TURN_ON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushEnabled").value(true))
                .andExpect(jsonPath("$.consentAgreedAt").value("2026-10-19T08:30:00"))
                .andExpect(jsonPath("$.consentRevokedAt").value(nullValue()));
        verify(service).change(eq(7L), argThat(body -> body.pushEnabled()
                && body.notificationConsent().agreed() && body.notificationConsent().policyVersion().equals("1.0")));
    }

    @Test
    void missingPushEnabledIsRejectedBeforeService() throws Exception {
        mvc.perform(put(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void turningOnWithoutConsentReturnsConflict() throws Exception {
        when(service.change(eq(7L), any())).thenThrow(new BusinessException(ErrorCode.CONSENT_REQUIRED, "동의가 필요해요."));
        mvc.perform(put(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pushEnabled\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void getReturnsSummaryForTokenMember() throws Exception {
        when(service.get(7)).thenReturn(new SettingSummary(true, true, "1.0", 2));
        mvc.perform(get(PATH).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentActive").value(true))
                .andExpect(jsonPath("$.consentPolicyVersion").value("1.0"))
                .andExpect(jsonPath("$.subscriptionCount").value(2));
    }

    private String bearer() { return "Bearer " + tokens.createAccessToken(7L, "test@example.test", "ROLE_USER"); }
}
