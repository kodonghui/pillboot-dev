package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.MedicationDoseQueryController;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DoseItem;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DoseResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationDoseQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MedicationDoseQueryController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class MedicationDoseQueryWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MedicationDoseQueryService service;
    private static final String PATH = "/api/calendar/doses";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PATH).param("date", DATE.toString())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void usesTokenOwnerForSelectedDateAndTime() throws Exception {
        when(service.get(5, DATE, "07:00")).thenReturn(new DoseResponse(DATE, "07:00", List.of(
                new DoseItem(25, 13, "관리자", "테스트약", "07:00", 2, "식후"))));
        mvc.perform(get(PATH).header("Authorization", bearer()).param("date", DATE.toString())
                        .param("time", "07:00").param("memberId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doses[0].myDrugId").value(25))
                .andExpect(jsonPath("$.doses[0].dosePerTime").value(2))
                .andExpect(jsonPath("$.time").value("07:00"));
        verify(service).get(5, DATE, "07:00");
    }

    @Test
    void omittedTimeQueriesTheWholeDayAndEmptyDayIs200() throws Exception {
        when(service.get(5, DATE, null)).thenReturn(new DoseResponse(DATE, null, List.of()));
        mvc.perform(get(PATH).header("Authorization", bearer()).param("date", DATE.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doses").isEmpty());
        verify(service).get(5, DATE, null);
    }

    @Test
    void missingAndMalformedDatesAre400() throws Exception {
        mvc.perform(get(PATH).header("Authorization", bearer())).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).header("Authorization", bearer()).param("date", "2026-02-30"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void invalidTimeReturnsTheExistingValidationError() throws Exception {
        when(service.get(5, DATE, "24:00")).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT, "복용 시각을 확인해주세요."));
        mvc.perform(get(PATH).header("Authorization", bearer()).param("date", DATE.toString()).param("time", "24:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(5L, "test@example.test", "ROLE_USER");
    }
}
