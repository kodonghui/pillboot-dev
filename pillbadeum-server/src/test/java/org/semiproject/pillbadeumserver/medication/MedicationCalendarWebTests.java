package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.MedicationCalendarController;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.CalendarResponse;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationCalendarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MedicationCalendarController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class MedicationCalendarWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MedicationCalendarService service;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 19);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);

    @Test
    void calendarRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/calendar").param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void returnsCalendarForTokenOwnerEvenIfAnotherMemberIdIsSupplied() throws Exception {
        var target = new MedicationTarget(25, 13, "관리자", "SELF", "테스트약", 1, "식후",
                FROM, TO, "07:00,11:00,17:00", LocalDateTime.of(2026, 10, 19, 9, 0));
        when(service.get(5, FROM, TO)).thenReturn(new CalendarResponse(FROM, TO, List.of(ScheduleResponse.from(target, false))));

        mvc.perform(get("/api/calendar").header("Authorization", bearer())
                        .param("from", FROM.toString()).param("to", TO.toString()).param("memberId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.to").value("2026-10-25"))
                .andExpect(jsonPath("$.schedules[0].myDrugId").value(25))
                .andExpect(jsonPath("$.schedules[0].profileId").value(13))
                .andExpect(jsonPath("$.schedules[0].doseTimes[0]").value("07:00"));
        verify(service).get(5, FROM, TO);
        verifyNoMoreInteractions(service);
    }

    @Test
    void noSchedulesReturnsAnEmptyListInsteadOfNotFound() throws Exception {
        when(service.get(5, FROM, TO)).thenReturn(new CalendarResponse(FROM, TO, List.of()));
        mvc.perform(get("/api/calendar").header("Authorization", bearer())
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schedules").isEmpty());
    }

    @Test
    void missingOrMalformedDatesAreRejectedBeforeService() throws Exception {
        mvc.perform(get("/api/calendar").header("Authorization", bearer()).param("from", FROM.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/calendar").header("Authorization", bearer())
                        .param("from", "2026-02-30").param("to", TO.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/calendar").header("Authorization", bearer())
                        .param("from", "tomorrow").param("to", TO.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void invalidRangeUsesTheExistingValidationErrorContract() throws Exception {
        when(service.get(5, TO, FROM)).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT, "조회 기간을 확인해주세요."));
        mvc.perform(get("/api/calendar").header("Authorization", bearer())
                        .param("from", TO.toString()).param("to", FROM.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(5L, "test@example.test", "ROLE_USER");
    }
}
