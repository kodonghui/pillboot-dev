package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.MedicationDrugChoiceController;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DrugChoices;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.service.MedicationDrugChoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MedicationDrugChoiceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class MedicationDrugChoiceWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MedicationDrugChoiceService service;
    private static final String PATH = "/api/profiles/13/drugs/schedule-choices";

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void usesTokenOwnerAndExposesUnscheduledMedicationsWithoutNullDateErrors() throws Exception {
        var target = new MedicationTarget(25, 13, "관리자", "SELF", "테스트약", 1, null,
                null, null, null, LocalDateTime.of(2026, 10, 19, 9, 0));
        when(service.get(5, 13)).thenReturn(new DrugChoices(List.of(ScheduleResponse.from(target, false))));

        mvc.perform(get(PATH).header("Authorization", bearer()).param("memberId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drugs[0].myDrugId").value(25))
                .andExpect(jsonPath("$.drugs[0].profileId").value(13))
                .andExpect(jsonPath("$.drugs[0].hasSchedule").value(false))
                .andExpect(jsonPath("$.drugs[0].doseTimes").isEmpty());
        verify(service).get(5, 13);
    }

    @Test
    void emptyOwnedProfileReturns200WhileForeignProfileReturns404() throws Exception {
        when(service.get(5, 13)).thenReturn(new DrugChoices(List.of()));
        when(service.get(5, 99)).thenThrow(new NotFoundException("조회할 수 있는 프로필이 없습니다."));
        mvc.perform(get(PATH).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.drugs").isEmpty());
        mvc.perform(get("/api/profiles/99/drugs/schedule-choices").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidProfileIdIsRejectedBeforeService() throws Exception {
        mvc.perform(get("/api/profiles/0/drugs/schedule-choices").header("Authorization", bearer())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/profiles/not-a-number/drugs/schedule-choices").header("Authorization", bearer())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String bearer() {
        return "Bearer " + tokens.createAccessToken(5L, "test@example.test", "ROLE_USER");
    }
}
