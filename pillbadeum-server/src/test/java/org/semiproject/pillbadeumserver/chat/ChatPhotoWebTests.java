package org.semiproject.pillbadeumserver.chat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.controller.ChatPhotoController;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.chat.service.ChatAnswerService;
import org.semiproject.pillbadeumserver.chat.service.ChatDrugLookup;
import org.semiproject.pillbadeumserver.chat.service.PhotoProposalService;
import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import org.semiproject.pillbadeumserver.chat.service.QuestionIntentReader;
import org.semiproject.pillbadeumserver.chat.service.QuestionService;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.service.RegistrationProposalService;
import org.semiproject.pillbadeumserver.chat.service.ScheduleProposalService;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** V3-CHAT-006: 실제 보안 필터·사진 1장 검사·진행 표식·응답 모양을 확인한다. 판독·저장은 대역이다. */
@WebMvcTest(controllers = ChatPhotoController.class)
@ActiveProfiles("test")
@Import({QuestionService.class, SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class ChatPhotoWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired QuestionService questions;
    @MockitoBean QuestionStore store;
    @MockitoBean QuestionClassifier classifier;
    @MockitoBean ChatAnswerService answers;
    @MockitoBean QuestionIntentReader intents;
    @MockitoBean RegistrationProposalService registrations;
    @MockitoBean ScheduleProposalService schedules;
    @MockitoBean PhotoProposalService photos;
    @MockitoBean ChatDrugLookup lookup;
    private final MockMultipartFile image = new MockMultipartFile("image", "rx.jpg", "image/jpeg", new byte[]{1, 2, 3});

    @BeforeEach
    void ownedRoom() {
        when(store.checkAccess(42L, 7L)).thenReturn(10L);
    }

    @Test
    void onePhotoAnswersLikeAQuestionAndReleasesTheRoom() throws Exception {
        when(photos.propose(eq(42L), eq(7L), eq(10L), any(), any())).thenReturn(QuestionResponse.answered(
                new QuestionResponse.Question(100L, 3), new QuestionResponse.Answer(101L, 3, "안내", null,
                        OffsetDateTime.parse("2026-11-02T09:00:00+09:00"))));
        mvc.perform(multipart("/api/chat-sessions/42/photos").file(image).header("Authorization", auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ANSWERED"))
                .andExpect(jsonPath("$.question.messageId").value(100))
                .andExpect(jsonPath("$.answer", aMapWithSize(6))).andExpect(jsonPath("$.answer.proposal").value(nullValue()));
        verify(photos).propose(eq(42L), eq(7L), eq(10L), argThat(file -> "rx.jpg".equals(file.getOriginalFilename())), any());
        assertThatRoomIsFree();
    }

    @Test
    void missingOrExtraPhotosAreRejectedBeforeTheService() throws Exception {
        mvc.perform(multipart("/api/chat-sessions/42/photos").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(multipart("/api/chat-sessions/42/photos").file(image)
                        .file(new MockMultipartFile("image", "rx2.jpg", "image/jpeg", new byte[]{4})).header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(multipart("/api/chat-sessions/42/photos").file(image)
                        .file(new MockMultipartFile("other", "x.jpg", "image/jpeg", new byte[]{4})).header("Authorization", auth()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat-sessions/42/photos").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(photos);
    }

    @Test
    void foreignRoomAndMissingLoginStopBeforeReading() throws Exception {
        when(store.checkAccess(42L, 7L)).thenThrow(new ForbiddenException());
        mvc.perform(multipart("/api/chat-sessions/42/photos").file(image).header("Authorization", auth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(multipart("/api/chat-sessions/42/photos").file(image)).andExpect(status().isUnauthorized());
        verifyNoInteractions(photos);
    }

    private void assertThatRoomIsFree() {
        org.assertj.core.api.Assertions.assertThat(questions.isInProgress(42L)).isFalse();
    }

    private String auth() { return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER"); }
}
