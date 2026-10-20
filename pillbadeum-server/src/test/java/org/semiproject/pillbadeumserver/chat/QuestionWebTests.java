package org.semiproject.pillbadeumserver.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import org.semiproject.pillbadeumserver.chat.service.ChatAnswerService;
import org.semiproject.pillbadeumserver.chat.service.AnswerFailedException;
import org.semiproject.pillbadeumserver.chat.controller.QuestionExceptionHandler;
import org.semiproject.pillbadeumserver.chat.service.QuestionService;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.chat.controller.QuestionController;
import org.semiproject.pillbadeumserver.common.config.CorsConfig;
import org.semiproject.pillbadeumserver.common.config.WebConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAccessDeniedHandler;
import org.semiproject.pillbadeumserver.common.security.JwtAuthenticationEntryPoint;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.common.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 보안 필터·Controller·조정 Service로 HTTP 계약을 확인한다. 분류·DB는 대역이다. */
@WebMvcTest(controllers = QuestionController.class)
@ActiveProfiles("test")
@Import({QuestionService.class, QuestionExceptionHandler.class, SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class QuestionWebTests {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean QuestionStore store;
    @MockitoBean QuestionClassifier classifier;
    @MockitoBean ChatAnswerService answers;

    @BeforeEach
    void ownedRoom() {
        when(store.checkAccess(42L, 7L)).thenReturn(10L);
    }

    @Test
    void passNormalizesTheQuestionAndUsesOnlyTheAuthenticatedMember() throws Exception {
        when(classifier.classify("복약 질문")).thenReturn(QuestionCategory.PASS);
        var question = new QuestionResponse.Question(100L, 1);
        when(store.save(42L, 7L, 10L, "복약 질문")).thenReturn(question);
        when(answers.complete(eq(42L), eq(7L), eq(10L), eq(question), any())).thenReturn(new QuestionResponse.Answer(
                101L, 1, "fixture answer\n@@SOURCES@@\n[]", null, java.time.OffsetDateTime.parse("2026-10-19T19:00:00+09:00")));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  복약 질문  \",\"memberId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.result").value("ANSWERED"))
                .andExpect(jsonPath("$.question", aMapWithSize(2)))
                .andExpect(jsonPath("$.question.messageId").value(100))
                .andExpect(jsonPath("$.question.turnNo").value(1))
                .andExpect(jsonPath("$.answer", aMapWithSize(5)))
                .andExpect(jsonPath("$.answer.messageId").value(101))
                .andExpect(jsonPath("$.answer.turnNo").value(1))
                .andExpect(jsonPath("$.answer.createdAt").value("2026-10-19T19:00:00+09:00"))
                .andExpect(jsonPath("$.notice").value(nullValue()))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        var order = inOrder(store, classifier, answers);
        order.verify(store).checkAccess(42L, 7L);
        order.verify(classifier).classify("복약 질문");
        order.verify(store).save(42L, 7L, 10L, "복약 질문");
        order.verify(answers).complete(eq(42L), eq(7L), eq(10L), eq(question), any());
    }

    @ParameterizedTest
    @EnumSource(value = QuestionCategory.class, names = {"OFF_TOPIC", "UNSAFE", "EMERGENCY"})
    void blockedCategoriesReturnFixedNoticesWithoutSaving(QuestionCategory category) throws Exception {
        when(classifier.classify("fixture")).thenReturn(category);
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.result").value("BLOCKED"))
                .andExpect(jsonPath("$.question").value(nullValue()))
                .andExpect(jsonPath("$.answer").value(nullValue()))
                .andExpect(jsonPath("$.notice.category").value(category.name()))
                .andExpect(jsonPath("$.notice.message").value(category.noticeMessage()));
        verify(store, never()).save(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void missingOrInvalidLoginStopsBeforeOwnershipAndAi() throws Exception {
        for (String token : new String[]{"", "Bearer invalid"}) {
            mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(store, classifier);
    }

    @Test
    void invalidIdsAndMalformedOrMissingBodiesDoNotCallAi() throws Exception {
        for (String id : new String[]{"0", "-1", "abc"}) {
            mvc.perform(post("/api/chat-sessions/{id}/messages", id).header("Authorization", authorization())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String body : new String[]{"", "null", "{", "{\"content\":{\"file\":\"fixture\"}}"}) {
            mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(store, classifier);
    }

    @Test
    void blankAndOverlongQuestionsStopBeforeClassification() throws Exception {
        for (String body : new String[]{"{}", "{\"content\":null}", "{\"content\":\"   \"}",
                new JsonMapper().writeValueAsString(java.util.Map.of("content", "가".repeat(1001)))}) {
            mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(classifier);
        verify(store, never()).save(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void thousandCharactersAfterStrippingAreAccepted() throws Exception {
        String question = "가".repeat(1000);
        when(classifier.classify(question)).thenReturn(QuestionCategory.OFF_TOPIC);
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(java.util.Map.of("content", " \n" + question + "\t "))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("BLOCKED"));
        verify(classifier).classify(question);
    }

    @Test
    void failedClassificationReleasesTheRoomForTheNextRequest() throws Exception {
        when(classifier.classify("fixture"))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_TIMEOUT, "잠시 후 다시 시도해 주세요."))
                .thenReturn(QuestionCategory.OFF_TOPIC);
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXTERNAL_TIMEOUT"))
                .andExpect(jsonPath("$", aMapWithSize(2)));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("BLOCKED"));
        verify(store, never()).save(anyLong(), anyLong(), anyLong(), any());
    }

    private String authorization() {
        return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER");
    }

    @Test
    void retryHasNoBodyAndUsesSavedQuestionWithoutClassificationOrInsert() throws Exception {
        var question = new QuestionResponse.Question(100L, 1);
        when(store.retryQuestion(42L, 7L, 10L, 100L)).thenReturn(question);
        when(answers.retry(eq(42L), eq(7L), eq(10L), eq(question), any())).thenReturn(new QuestionResponse.Answer(
                101L, 1, "answer", null, java.time.OffsetDateTime.parse("2026-10-20T10:00:00+09:00")));
        mvc.perform(post("/api/chat-sessions/42/messages/100/retry").header("Authorization", authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ANSWERED"))
                .andExpect(jsonPath("$.question.messageId").value(100)).andExpect(jsonPath("$.answer.turnNo").value(1));
        verifyNoInteractions(classifier);
        verify(store, never()).save(any(), any(), any(), any());
    }

    @Test
    void retryAuthenticationValidationAndDomainErrorRemainDistinct() throws Exception {
        mvc.perform(post("/api/chat-sessions/42/messages/100/retry")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat-sessions/42/messages/0/retry").header("Authorization", authorization()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(store.retryQuestion(42L, 7L, 10L, 100L)).thenThrow(new BusinessException(ErrorCode.RETRY_NOT_ALLOWED, "마지막 미응답 질문만 가능합니다."));
        mvc.perform(post("/api/chat-sessions/42/messages/100/retry").header("Authorization", authorization()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RETRY_NOT_ALLOWED"));
        verifyNoInteractions(classifier, answers);
    }

    @Test
    void failureAfterQuestionCommitHasItsIdAndTheNextRequestIsAllowed() throws Exception {
        when(classifier.classify("fixture")).thenReturn(QuestionCategory.PASS);
        when(store.save(42L, 7L, 10L, "fixture")).thenReturn(new QuestionResponse.Question(100L, 1));
        when(answers.complete(eq(42L), eq(7L), eq(10L), any(), any()))
                .thenThrow(new AnswerFailedException(100L, new BusinessException(ErrorCode.EXTERNAL_API_TIMEOUT, "private fixture")));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.code").value("EXTERNAL_TIMEOUT"))
                .andExpect(jsonPath("$.questionMessageId").value(100))
                .andExpect(jsonPath("$.message").value("답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요."));
        doReturn(QuestionCategory.OFF_TOPIC).when(classifier).classify("fixture");
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("BLOCKED"));
    }
}
