package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.MedicationVoiceScheduleController;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.*;
import org.semiproject.pillbadeumserver.medication.service.MedicationVoiceScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MedicationVoiceScheduleController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class MedicationVoiceScheduleWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MedicationVoiceScheduleService service;
    private static final String PATH = "/api/schedule-voice-commands";
    private static final String BODY = """
            {"text":"일주일 동안 삼시세끼 약 일정 추가해줘","selectedProfileId":13,"selectedMyDrugId":25,"selectedDate":"2026-11-03"}
            """;

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void usesTokenOwnerAndReturnsSavedScheduleEvenWhenAudioIsUnavailable() throws Exception {
        var schedule = new ScheduleResponse(25, 13, "관리자", "테스트약", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 9),
                List.of("07:00", "11:00", "17:00"), 1, null, false, true);
        when(service.execute(eq(5L), any())).thenReturn(new CommandResponse(schedule, "관리자님의 약 일정을 저장했습니다.",
                List.of("myDrugId", "startDate"), AudioStatus.UNAVAILABLE, null, null, false));
        mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.myDrugId").value(25))
                .andExpect(jsonPath("$.audioStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.appliedDefaults[0]").value("myDrugId"));
        verify(service).execute(eq(5L), argThat(request -> request.selectedDate().equals(LocalDate.of(2026, 11, 3))));
    }

    @Test
    void missingSelectionBlankTextAndInvalidDateAreRejectedBeforeAi() throws Exception {
        for (String body : List.of("{}", BODY.replace("일주일 동안 삼시세끼 약 일정 추가해줘", " "),
                BODY.replace("\"selectedDate\":\"2026-11-03\"", "\"selectedDate\":\"2026-02-30\""),
                BODY.replace("\"selectedMyDrugId\":25", "\"selectedMyDrugId\":0"))) {
            mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void aiNotConfiguredReturns503() throws Exception {
        when(service.execute(eq(5L), any())).thenThrow(new AiUnavailableException("AI 설정이 없습니다."));
        mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(5L, "test@example.test", "ROLE_USER");
    }
}
