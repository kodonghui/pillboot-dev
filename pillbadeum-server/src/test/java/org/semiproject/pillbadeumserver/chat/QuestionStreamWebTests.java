package org.semiproject.pillbadeumserver.chat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.controller.QuestionStreamController;
import org.semiproject.pillbadeumserver.chat.controller.QuestionStreams;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.chat.service.AnswerFailedException;
import org.semiproject.pillbadeumserver.chat.service.ChatAnswerService;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.ChatStage;
import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import org.semiproject.pillbadeumserver.chat.service.QuestionIntent;
import org.semiproject.pillbadeumserver.chat.service.QuestionIntentReader;
import org.semiproject.pillbadeumserver.chat.service.ChatDrugLookup;
import org.semiproject.pillbadeumserver.chat.service.PhotoProposalService;
import org.semiproject.pillbadeumserver.chat.service.RegistrationProposalService;
import org.semiproject.pillbadeumserver.chat.service.ScheduleProposalService;
import org.semiproject.pillbadeumserver.chat.service.QuestionPlan;
import org.semiproject.pillbadeumserver.chat.service.QuestionService;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * V3-CHAT-008: 실제 보안 필터·스트림 Controller·조정 Service로 SSE 계약을 확인한다. 분류·DB·답변은 대역이다.
 * 스트림을 열기 전 오류는 기존 JSON 오류, 연 뒤에는 stage 이벤트들과 마지막 result 또는 error 하나다.
 */
@WebMvcTest(controllers = QuestionStreamController.class)
@ActiveProfiles("test")
@Import({QuestionService.class, QuestionStreams.class, SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, CorsConfig.class, WebConfig.class, GlobalExceptionHandler.class})
class QuestionStreamWebTests {
    private static final String ANSWER = "fixture answer\n@@SOURCES@@\n[]";
    private final JsonMapper json = new JsonMapper();
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

    @TestConfiguration
    static class Threads {
        @Bean(name = "applicationTaskExecutor")
        AsyncTaskExecutor applicationTaskExecutor() {
            var executor = new SimpleAsyncTaskExecutor("stream-test-");
            executor.setVirtualThreads(true);
            return executor;
        }

        @Bean
        TaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.initialize();
            return scheduler;
        }
    }

    @BeforeEach
    void ownedRoom() {
        when(store.checkAccess(42L, 7L)).thenReturn(10L);
        when(store.classificationContext(42L, 7L, 10L)).thenReturn(QuestionClassifier.Context.empty());
        when(intents.read(any(), any(), any(), any())).thenReturn(QuestionIntent.question());
    }

    @Test
    void answeredQuestionStreamsTheStagesItPassedThenTheSameJsonBody() throws Exception {
        when(classifier.plan(eq("복약 질문"), any())).thenReturn(plan(QuestionCategory.PASS));
        var question = new QuestionResponse.Question(100L, 1);
        when(store.save(42L, 7L, 10L, "복약 질문")).thenReturn(question);
        when(answers.complete(eq(42L), eq(7L), eq(10L), eq(question), any(), any())).thenAnswer(invocation -> {
            ChatDeadline deadline = invocation.getArgument(4);
            deadline.stage(ChatStage.FINDING_DRUG);
            deadline.stage(ChatStage.CHECKING);
            deadline.stage(ChatStage.WRITING);
            return new QuestionResponse.Answer(101L, 1, ANSWER, null, OffsetDateTime.parse("2026-11-02T19:30:00+09:00"));
        });

        var response = mvc.perform(ask("{\"content\":\"  복약 질문  \",\"memberId\":999}"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        var events = events(response);

        assertThat(response.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(events).extracting(Event::name).containsExactly("stage", "stage", "stage", "stage", "result");
        assertThat(events.subList(0, 4)).extracting(e -> e.data().get("stage").asString())
                .containsExactly("UNDERSTANDING", "FINDING_DRUG", "CHECKING", "WRITING");
        var result = events.getLast().data();
        assertThat(result.get("result").asString()).isEqualTo("ANSWERED");
        assertThat(result.get("question").get("messageId").asLong()).isEqualTo(100);
        assertThat(result.get("answer").get("messageId").asLong()).isEqualTo(101);
        assertThat(result.get("answer").get("content").asString()).isEqualTo(ANSWER);
        assertThat(result.get("notice").isNull()).isTrue();
        assertThat(questions.isInProgress(42L)).isFalse();
    }

    @Test
    void blockedQuestionStreamsOnlyTheFirstStageAndIsNotSaved() throws Exception {
        when(classifier.plan(eq("fixture"), any())).thenReturn(plan(QuestionCategory.OFF_TOPIC));
        var events = events(mvc.perform(ask("{\"content\":\"fixture\"}")).andExpect(request().asyncStarted()).andReturn());
        assertThat(events).extracting(Event::name).containsExactly("stage", "result");
        assertThat(events.getLast().data().get("result").asString()).isEqualTo("BLOCKED");
        assertThat(events.getLast().data().get("notice").get("category").asString()).isEqualTo("OFF_TOPIC");
        verify(store, never()).save(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void errorsBeforeTheStreamOpensKeepTheExistingJsonResponses() throws Exception {
        mvc.perform(post("/api/chat-sessions/42/messages/stream").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(ask("{\"content\":\"   \"}"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(store.checkAccess(43L, 7L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "상담방을 찾을 수 없습니다."));
        mvc.perform(post("/api/chat-sessions/43/messages/stream").header("Authorization", authorization())
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").exists());
        verifyNoInteractions(classifier, answers);
    }

    @Test
    void secondQuestionWhileTheFirstIsAnsweringIsRejectedAsJson409() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(classifier.plan(eq("fixture"), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return plan(QuestionCategory.OFF_TOPIC);
        });
        var first = mvc.perform(ask("{\"content\":\"fixture\"}")).andExpect(request().asyncStarted()).andReturn();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        mvc.perform(ask("{\"content\":\"fixture\"}"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
        release.countDown();
        assertThat(events(first).getLast().data().get("result").asString()).isEqualTo("BLOCKED");
        assertThat(questions.isInProgress(42L)).isFalse();
    }

    @Test
    void failureAfterTheQuestionIsSavedEndsWithAnErrorEventCarryingItsId() throws Exception {
        when(classifier.plan(eq("fixture"), any())).thenReturn(plan(QuestionCategory.PASS));
        when(store.save(42L, 7L, 10L, "fixture")).thenReturn(new QuestionResponse.Question(100L, 1));
        when(answers.complete(eq(42L), eq(7L), eq(10L), any(), any(), any()))
                .thenThrow(new AnswerFailedException(100L, new BusinessException(ErrorCode.EXTERNAL_API_TIMEOUT, "private fixture")));

        var events = events(mvc.perform(ask("{\"content\":\"fixture\"}")).andExpect(request().asyncStarted()).andReturn());
        assertThat(events).extracting(Event::name).containsExactly("stage", "error");
        var error = events.getLast().data();
        assertThat(error.get("status").asInt()).isEqualTo(503);
        assertThat(error.get("code").asString()).isEqualTo("EXTERNAL_TIMEOUT");
        assertThat(error.get("message").asString()).isEqualTo("답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
        assertThat(error.get("questionMessageId").asLong()).isEqualTo(100);
        assertThat(questions.isInProgress(42L)).isFalse();
    }

    @Test
    void unexpectedFailureSendsOnlyTheGenericInternalError() throws Exception {
        when(classifier.plan(eq("fixture"), any())).thenThrow(new IllegalStateException("private table detail"));
        var events = events(mvc.perform(ask("{\"content\":\"fixture\"}")).andExpect(request().asyncStarted()).andReturn());
        var error = events.getLast().data();
        assertThat(events.getLast().name()).isEqualTo("error");
        assertThat(error.get("status").asInt()).isEqualTo(500);
        assertThat(error.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.get("questionMessageId").isNull()).isTrue();
        assertThat(error.toString()).doesNotContain("private table detail");
        assertThat(questions.isInProgress(42L)).isFalse();
    }

    @Test
    void retryStreamsTheSavedQuestionAndReportsRetryRulesInsideTheStream() throws Exception {
        var question = new QuestionResponse.Question(100L, 1);
        when(store.retryQuestion(42L, 7L, 10L, 100L)).thenReturn(question)
                .thenThrow(new BusinessException(ErrorCode.RETRY_NOT_ALLOWED, "마지막 미응답 질문만 가능합니다."));
        when(answers.retry(eq(42L), eq(7L), eq(10L), eq(question), any())).thenReturn(new QuestionResponse.Answer(
                101L, 1, ANSWER, null, OffsetDateTime.parse("2026-11-02T19:30:00+09:00")));

        var answered = events(mvc.perform(retry("100")).andExpect(request().asyncStarted()).andReturn());
        assertThat(answered).extracting(Event::name).containsExactly("stage", "result");
        assertThat(answered.getLast().data().get("answer").get("messageId").asLong()).isEqualTo(101);

        var refused = events(mvc.perform(retry("100")).andExpect(request().asyncStarted()).andReturn());
        assertThat(refused.getLast().name()).isEqualTo("error");
        assertThat(refused.getLast().data().get("status").asInt()).isEqualTo(400);
        assertThat(refused.getLast().data().get("code").asString()).isEqualTo("RETRY_NOT_ALLOWED");

        mvc.perform(retry("0")).andExpect(request().asyncNotStarted())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(classifier);
    }

    private MockHttpServletRequestBuilder ask(String body) {
        return post("/api/chat-sessions/42/messages/stream").header("Authorization", authorization())
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder retry(String messageId) {
        return post("/api/chat-sessions/42/messages/{messageId}/retry/stream", messageId).header("Authorization", authorization())
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON);
    }

    /** 스트림이 닫힐 때까지 기다린 뒤 이벤트(이름·JSON 본문)로 나눈다. 주석 줄(keep-alive)은 건너뛴다. */
    private List<Event> events(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        result.getAsyncResult(5_000);
        var events = new ArrayList<Event>();
        for (String block : result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\n\n")) {
            String name = null;
            var data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) name = line.substring(6).strip();
                else if (line.startsWith("data:")) data.append(line.substring(5));
            }
            if (name != null) events.add(new Event(name, json.readTree(data.toString())));
        }
        return events;
    }

    private record Event(String name, JsonNode data) {}

    private String authorization() {
        return "Bearer " + tokens.createAccessToken(7L, "fixture@example.test", "ROLE_USER");
    }

    private static QuestionPlan plan(QuestionCategory category) {
        return new QuestionPlan(category, QuestionPlan.Purpose.COMBINED, "fixture");
    }
}
