package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.exception.AiUnavailableException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.*;
import org.semiproject.pillbadeumserver.medication.controller.MedicationLiveController;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;
import org.semiproject.pillbadeumserver.medication.service.MedicationLiveSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MedicationLiveController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, GlobalExceptionHandler.class})
class MedicationLiveWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean MedicationLiveSessionService service;
    private static final String PATH = "/api/medication/live/sessions";
    private static final String BODY = """
            {"sdp":"offer","selectedProfileId":13,"selectedMyDrugId":25,"selectedDate":"2026-11-03"}
            """;
    private static final Instant EXPIRES = Instant.parse("2026-10-19T03:05:00Z");

    @Test
    void everySessionRouteRequiresAuthentication() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/local-id")).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + "/local-id")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void createsSessionWithAuthenticatedMemberAndReturnsOnlyConnectionInformation() throws Exception {
        when(service.start(eq(5L), any())).thenReturn(new StartResponse("local-id", "answer", EXPIRES));
        mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sessionId").value("local-id"))
                .andExpect(jsonPath("$.sdp").value("answer"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
        verify(service).start(eq(5L), argThat(request -> request.selectedMyDrugId() == 25 && request.sdp().equals("offer")));
    }

    @Test
    void missingSelectionAndInvalidDatesAreRejectedBeforeCreatingASession() throws Exception {
        for (String body : List.of("{}", BODY.replace("\"sdp\":\"offer\"", "\"sdp\":\"\""),
                BODY.replace("\"selectedDate\":\"2026-11-03\"", "\"selectedDate\":\"2026-02-30\""),
                BODY.replace("\"selectedProfileId\":13", "\"selectedProfileId\":0"))) {
            mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void medicationSelectionIsOptionalButProfileAndPositiveIdsAreStillRequired() throws Exception {
        when(service.start(eq(5L), any())).thenReturn(new StartResponse("local-id", "answer", EXPIRES));
        for (String body : List.of(BODY.replace("\"selectedMyDrugId\":25,", ""),
                BODY.replace("\"selectedMyDrugId\":25", "\"selectedMyDrugId\":null"))) {
            mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
        verify(service, times(2)).start(eq(5L), argThat(request -> request.selectedMyDrugId() == null));
        mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"selectedMyDrugId\":25", "\"selectedMyDrugId\":0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void progressAndStopUseTheSameAuthenticatedOwner() throws Exception {
        when(service.get(5, "local-id")).thenReturn(new Progress("local-id", "WAITING_FOR_DETAILS", "몇 시에 등록할까요?",
                List.of("doseTimes"), List.of(), null, EXPIRES));
        mvc.perform(get(PATH + "/local-id").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.missingFields[0]").value("doseTimes"));
        mvc.perform(delete(PATH + "/local-id").header("Authorization", bearer()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).stop(5, "local-id");
    }

    @Test
    void missingSessionAndMissingApiConfigurationUseExistingErrorContract() throws Exception {
        when(service.get(5, "unknown")).thenThrow(new NotFoundException("대화가 없습니다."));
        when(service.start(eq(5L), any())).thenThrow(new AiUnavailableException("Live 설정이 없습니다."));
        mvc.perform(get(PATH + "/unknown").header("Authorization", bearer())).andExpect(status().isNotFound());
        mvc.perform(post(PATH).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void deletionProgressReportsTheAffectedScheduleWithoutPretendingToSaveIt() throws Exception {
        when(service.get(5, "local-id")).thenReturn(new Progress("local-id", "DELETED", "일정을 삭제했어요.",
                List.of(), List.of(), null, EXPIRES, new CompletedOperation(Operation.DELETE, 14, 27)));
        mvc.perform(get(PATH + "/local-id").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.completedOperation.action").value("DELETE"))
                .andExpect(jsonPath("$.completedOperation.profileId").value(14))
                .andExpect(jsonPath("$.completedOperation.myDrugId").value(27))
                .andExpect(jsonPath("$.savedSchedule").isEmpty());
    }

    private String bearer() { return "Bearer " + tokens.createAccessToken(5L, "fixture@example.test", "ROLE_USER"); }
}
