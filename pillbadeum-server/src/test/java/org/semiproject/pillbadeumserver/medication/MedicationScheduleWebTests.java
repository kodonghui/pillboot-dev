package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.MedicationScheduleController;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationScheduleService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MedicationScheduleController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class MedicationScheduleWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MedicationScheduleService service;
    private static final String PATH = "/api/profiles/2/drugs/3/schedule";
    private static final String VALID_BODY = """
            {"startDate":"2026-10-19","endDate":"2026-10-25","doseTimes":["07:00","17:00"],"dosePerTime":1,"timing":"식후"}
            """;

    @Test
    void scheduleRoutesRequireAuthentication() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/stop")).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void putUsesMemberFromBearerTokenAndReturnsBusinessDto() throws Exception {
        when(service.save(eq(7L), eq(2L), eq(3L), any())).thenReturn(response());
        mvc.perform(put(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.myDrugId").value(3))
                .andExpect(jsonPath("$.doseTimes[0]").value("07:00"))
                .andExpect(jsonPath("$.hasSchedule").value(true));
        verify(service).save(eq(7L), eq(2L), eq(3L), argThat(body -> body.dosePerTime() == 1 && body.timing().equals("식후")));
    }

    @Test
    void missingEndDateAndOutOfRangeDoseAreRejectedBeforeService() throws Exception {
        mvc.perform(put(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"endDate\":\"2026-10-25\",", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"dosePerTime\":1", "\"dosePerTime\":11")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void getAndStopUseTheSameAuthenticatedOwner() throws Exception {
        when(service.get(7, 2, 3)).thenReturn(response());
        when(service.stop(7, 2, 3)).thenReturn(response());
        mvc.perform(get(PATH).header("Authorization", bearer())).andExpect(status().isOk());
        mvc.perform(post(PATH + "/stop").header("Authorization", bearer())).andExpect(status().isOk());
        verify(service).get(7, 2, 3);
        verify(service).stop(7, 2, 3);
    }

    @Test
    void deleteReturnsNoContentAndUsesAuthenticatedOwner() throws Exception {
        mvc.perform(delete(PATH).header("Authorization", bearer()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).delete(7, 2, 3);
    }

    @Test
    void missingScheduleIsNotReportedAsSuccessfulDeletion() throws Exception {
        doThrow(new NotFoundException("등록된 복약 일정이 없습니다.")).when(service).delete(7, 2, 3);
        mvc.perform(delete(PATH).header("Authorization", bearer())).andExpect(status().isNotFound());
    }

    private String bearer() { return "Bearer " + tokens.createAccessToken(7L, "test@example.test", "ROLE_USER"); }

    private ScheduleResponse response() {
        return new ScheduleResponse(3, 2, "엄마", "테스트약", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25),
                List.of("07:00", "17:00"), 1, "식후", false, true);
    }
}
