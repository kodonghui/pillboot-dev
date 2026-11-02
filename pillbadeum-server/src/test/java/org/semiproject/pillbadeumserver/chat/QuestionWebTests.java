package org.semiproject.pillbadeumserver.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import java.util.List;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.service.QuestionIntent;
import org.semiproject.pillbadeumserver.chat.service.QuestionIntentReader;
import org.semiproject.pillbadeumserver.chat.service.PhotoProposalService;
import org.semiproject.pillbadeumserver.chat.service.RegistrationProposalService;
import org.semiproject.pillbadeumserver.chat.service.QuestionPlan;
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
    @MockitoBean QuestionIntentReader intents;
    @MockitoBean RegistrationProposalService registrations;
    @MockitoBean PhotoProposalService photos;

    @BeforeEach
    void ownedRoom() {
        when(store.checkAccess(42L, 7L)).thenReturn(10L);
        when(store.classificationContext(42L, 7L, 10L)).thenReturn(QuestionClassifier.Context.empty());
        when(intents.read(any(), any(), any())).thenReturn(QuestionIntent.question());
    }

    @Test
    void passNormalizesTheQuestionAndUsesOnlyTheAuthenticatedMember() throws Exception {
        when(classifier.plan(eq("복약 질문"), any())).thenReturn(plan(QuestionCategory.PASS));
        var question = new QuestionResponse.Question(100L, 1);
        when(store.save(42L, 7L, 10L, "복약 질문")).thenReturn(question);
        when(answers.complete(eq(42L), eq(7L), eq(10L), eq(question), any(), any())).thenReturn(new QuestionResponse.Answer(
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
                // v3: 제안 카드 칸(proposal)이 생김. 약 질문 답변은 null(openapi-v3 submitQuestion)
                .andExpect(jsonPath("$.answer", aMapWithSize(6)))
                .andExpect(jsonPath("$.answer.proposal").value(nullValue()))
                .andExpect(jsonPath("$.answer.messageId").value(101))
                .andExpect(jsonPath("$.answer.turnNo").value(1))
                .andExpect(jsonPath("$.answer.createdAt").value("2026-10-19T19:00:00+09:00"))
                .andExpect(jsonPath("$.notice").value(nullValue()))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        var order = inOrder(store, classifier, answers);
        order.verify(store).checkAccess(42L, 7L);
        order.verify(classifier).plan(eq("복약 질문"), any());
        order.verify(store).save(42L, 7L, 10L, "복약 질문");
        order.verify(answers).complete(eq(42L), eq(7L), eq(10L), eq(question), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = QuestionCategory.class, names = {"OFF_TOPIC", "UNSAFE", "EMERGENCY"})
    void blockedCategoriesReturnFixedNoticesWithoutSaving(QuestionCategory category) throws Exception {
        when(classifier.plan(eq("fixture"), any())).thenReturn(plan(category));
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
        when(classifier.plan(eq(question), any())).thenReturn(plan(QuestionCategory.OFF_TOPIC));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(java.util.Map.of("content", " \n" + question + "\t "))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("BLOCKED"));
        verify(classifier).plan(eq(question), any());
    }

    @Test
    void failedClassificationReleasesTheRoomForTheNextRequest() throws Exception {
        when(classifier.plan(eq("fixture"), any()))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_TIMEOUT, "잠시 후 다시 시도해 주세요."))
                .thenReturn(plan(QuestionCategory.OFF_TOPIC));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXTERNAL_TIMEOUT"))
                .andExpect(jsonPath("$", aMapWithSize(2)));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("BLOCKED"));
        verify(store, never()).save(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void registrationRequestAnswersWithTheProposalCardInsteadOfTheModelAnswer() throws Exception {
        when(classifier.plan(eq("타이레놀 500 등록해 줘"), any())).thenReturn(plan(QuestionCategory.PASS));
        when(store.classificationContext(42L, 7L, 10L)).thenReturn(new QuestionClassifier.Context(List.of(), List.of(),
                List.of("가상혈압정")));
        when(intents.read(eq("타이레놀 500 등록해 줘"), eq(List.of("가상혈압정")), any())).thenReturn(
                new QuestionIntent(QuestionIntent.Kind.REGISTER_DRUG, List.of("타이레놀 500"), null));
        var question = new QuestionResponse.Question(100L, 1);
        when(store.save(42L, 7L, 10L, "타이레놀 500 등록해 줘")).thenReturn(question);
        var created = java.time.LocalDateTime.of(2026, 11, 2, 19, 0);
        var card = ChatProposalResponse.of(500L, ChatProposalResponse.Kind.REGISTER, ChatProposalResponse.Status.PENDING,
                10L, 101L, created, created.plusMinutes(30), null, List.of(new ChatProposalResponse.Item(1, "199900002",
                        "타이레놀정500밀리그람(아세트아미노펜)", "시험제약", null, "타이레놀 500", StrengthMatch.MATCH, false, false,
                        null, "타이레놀정", "500밀리그람")), created.plusMinutes(1));
        when(registrations.propose(eq(42L), eq(7L), eq(10L), eq(question), eq(List.of("타이레놀 500")), any(), eq(false)))
                .thenReturn(new QuestionResponse.Answer(101L, 1, "등록할 약 후보를 찾았어요.", null,
                        java.time.OffsetDateTime.parse("2026-11-02T19:00:00+09:00"), card));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"타이레놀 500 등록해 줘\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ANSWERED"))
                .andExpect(jsonPath("$.answer.proposal.proposalId").value(500))
                .andExpect(jsonPath("$.answer.proposal.kind").value("REGISTER"))
                .andExpect(jsonPath("$.answer.proposal.status").value("PENDING"))
                .andExpect(jsonPath("$.answer.proposal.expiresAt").value("2026-11-02T19:30:00+09:00"))
                .andExpect(jsonPath("$.answer.proposal.decidedAt").value(nullValue()))
                .andExpect(jsonPath("$.answer.proposal.schedule").value(nullValue()))
                .andExpect(jsonPath("$.answer.proposal.items[0].itemNo").value(1))
                .andExpect(jsonPath("$.answer.proposal.items[0].mention").value("타이레놀 500"))
                .andExpect(jsonPath("$.answer.proposal.items[0].strengthMatch").value("MATCH"))
                .andExpect(jsonPath("$.answer.proposal.items[0].alreadyRegistered").value(false))
                .andExpect(jsonPath("$.answer.proposal.items[0].myDrugId").value(nullValue()))
                .andExpect(jsonPath("$.answer.proposal.items[0].baseName").value("타이레놀정"));
        verifyNoInteractions(answers);
    }

    @Test
    void retryOfARegistrationRequestAnswersWithAProposalAgain() throws Exception {
        var question = new QuestionResponse.Question(100L, 1);
        when(store.retryQuestion(42L, 7L, 10L, 100L)).thenReturn(question);
        when(store.questionContent(42L, question)).thenReturn("타이레놀 등록해 줘");
        when(intents.read(eq("타이레놀 등록해 줘"), any(), any())).thenReturn(
                new QuestionIntent(QuestionIntent.Kind.REGISTER_DRUG, List.of("타이레놀"), null));
        when(registrations.propose(eq(42L), eq(7L), eq(10L), eq(question), eq(List.of("타이레놀")), any(), eq(true)))
                .thenReturn(new QuestionResponse.Answer(101L, 1, "후보 없음", null,
                        java.time.OffsetDateTime.parse("2026-11-02T19:00:00+09:00")));
        mvc.perform(post("/api/chat-sessions/42/messages/100/retry").header("Authorization", authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer.content").value("후보 없음"))
                .andExpect(jsonPath("$.answer.proposal").value(nullValue()));
        verifyNoInteractions(answers);
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
        when(classifier.plan(eq("fixture"), any())).thenReturn(plan(QuestionCategory.PASS));
        when(store.save(42L, 7L, 10L, "fixture")).thenReturn(new QuestionResponse.Question(100L, 1));
        when(answers.complete(eq(42L), eq(7L), eq(10L), any(), any(), any()))
                .thenThrow(new AnswerFailedException(100L, new BusinessException(ErrorCode.EXTERNAL_API_TIMEOUT, "private fixture")));
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.code").value("EXTERNAL_TIMEOUT"))
                .andExpect(jsonPath("$.questionMessageId").value(100))
                .andExpect(jsonPath("$.message").value("답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요."));
        doReturn(plan(QuestionCategory.OFF_TOPIC)).when(classifier).plan(eq("fixture"), any());
        mvc.perform(post("/api/chat-sessions/42/messages").header("Authorization", authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("BLOCKED"));
    }
    private static QuestionPlan plan(QuestionCategory category) {
        return new QuestionPlan(category, QuestionPlan.Purpose.COMBINED, "fixture");
    }

}
