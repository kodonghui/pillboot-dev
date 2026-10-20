package org.semiproject.pillbadeumserver.chat;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.semiproject.pillbadeumserver.chat.service.QuestionClassifier;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.service.ChatAnswerService;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessageRepository;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CHAT-004의 질문 저장을 실제 MySQL의 커밋·행 잠금·롤백으로 검증한다. 분류·후속 답변은 대역이다.
 * 실제 커밋을 확인하므로 테스트 전체를 트랜잭션으로 감싸지 않고 실행별 합성 자료만 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class QuestionMySqlTests {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String QUESTION = "synthetic-private-question";
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired QuestionStore store;
    @MockitoBean QuestionClassifier classifier;
    @MockitoBean ChatAnswerService answers;
    @MockitoSpyBean ChatMessageRepository messages;
    private final List<Long> members = new ArrayList<>();
    private long memberId;
    private long profileId;
    private long sessionId;

    @BeforeEach
    void prepareDedicatedDatabaseFixtures() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var connection = dataSource.getConnection()) {
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }
        memberId = member();
        profileId = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) "
                + "VALUES(?, 'fixture', 'SELF', 2000, 'FEMALE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", memberId);
        sessionId = session(memberId, profileId);
        when(classifier.classify(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return QuestionCategory.PASS;
        });
        when(answers.complete(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            QuestionResponse.Question question = invocation.getArgument(3);
            return new QuestionResponse.Answer(999L, question.turnNo(), "fixture answer", null,
                    java.time.OffsetDateTime.now(TimeZoneConfig.ZONE));
        });
    }

    @AfterEach
    void removeOnlyThisTestsFixtures() {
        for (Long member : members) {
            jdbc.update("DELETE m FROM chat_message m JOIN chat_session s ON s.session_id=m.session_id WHERE s.member_id=?", member);
            jdbc.update("DELETE FROM chat_session WHERE member_id=?", member);
        }
        for (Long member : members) {
            jdbc.update("DELETE FROM profile WHERE member_id=?", member);
            jdbc.update("DELETE FROM member WHERE member_id=?", member);
        }
        members.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void firstPassCommitsTheLatestProfileAndQuestionTogether(boolean pregnant) throws Exception {
        when(classifier.classify(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // 분류 대기 중의 프로필 수정도 첫 PASS 저장 시점에 읽어야 한다.
            jdbc.update("UPDATE profile SET profile_birth_year=1991,profile_gender='MALE',profile_pregnant=? WHERE profile_id=?", pregnant, profileId);
            return QuestionCategory.PASS;
        });
        String question = "가".repeat(19) + "😀" + "나머지 질문";
        var result = send(sessionId, " \t" + question + "\n ", 200);
        assertThat(body(result).path("question").path("turnNo").asInt()).isEqualTo(1);
        var saved = jdbc.queryForMap("SELECT * FROM chat_message WHERE session_id=?", sessionId);
        assertThat(saved.get("message_id")).isEqualTo(body(result).path("question").path("messageId").asLong());
        assertThat(saved).containsEntry("trun_no", 1).containsEntry("role", "user")
                .containsEntry("content", question).containsEntry("token_count", null);
        var session = room();
        assertThat(session).containsEntry("snap_birth_year", 1991L).containsEntry("snap_gender", "MALE")
                .containsEntry("snap_pregnant", pregnant).containsEntry("title", "가".repeat(19) + "😀…")
                .containsEntry("created_at", LocalDateTime.parse("2020-01-02T03:04:05")).containsEntry("last_response_id", null);
        LocalDateTime now = LocalDateTime.now(TimeZoneConfig.ZONE);
        assertThat((LocalDateTime) session.get("updated_at")).isBetween(now.minusSeconds(10), now.plusSeconds(1));
        assertThat(saved.get("created_at")).isEqualTo(session.get("updated_at"));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void laterQuestionsKeepTheFirstSnapshotTitleAndLegacyNull() throws Exception {
        send(sessionId, "첫 질문", 200);
        jdbc.update("UPDATE profile SET profile_birth_year=1980,profile_gender='MALE',profile_pregnant=TRUE WHERE profile_id=?", profileId);
        jdbc.update("UPDATE chat_session SET snap_pregnant=NULL,updated_at='2020-01-02 03:04:05' WHERE session_id=?", sessionId);
        var before = room();
        jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,1,'assistant','fixture answer')", sessionId);
        var result = send(sessionId, "다음 질문", 200);
        assertThat(body(result).path("question").path("turnNo").asInt()).isEqualTo(2);
        var after = room();
        assertThat(after.remove("updated_at")).isNotEqualTo(before.remove("updated_at"));
        assertThat(after).isEqualTo(before);
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void assistantOnlyLegacyMessagesDoNotCountAsTheFirstUser() throws Exception {
        jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,4,'assistant','fixture')", sessionId);
        jdbc.update("UPDATE profile SET profile_pregnant=TRUE WHERE profile_id=?", profileId);
        var result = send(sessionId, "첫 사용자 질문", 200);
        assertThat(body(result).path("question").path("turnNo").asInt()).isEqualTo(5);
        assertThat(room()).containsEntry("snap_pregnant", true).containsEntry("title", "첫 사용자 질문");
    }

    @ParameterizedTest
    @EnumSource(value = QuestionCategory.class, names = {"OFF_TOPIC", "UNSAFE", "EMERGENCY"})
    void blockingDoesNotChangeAnySessionColumnOrConsumeATurn(QuestionCategory category, CapturedOutput output) throws Exception {
        var before = room();
        when(classifier.classify(QUESTION)).thenReturn(category);
        send(sessionId, QUESTION, 200);
        assertThat(count()).isZero();
        assertThat(room()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain(QUESTION);
        when(classifier.classify(QUESTION)).thenReturn(QuestionCategory.PASS);
        assertThat(body(send(sessionId, QUESTION, 200)).path("question").path("turnNo").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"EXTERNAL_API_TIMEOUT", "EXTERNAL_API_FAILED"})
    void classificationFailureLeavesAllRowsUntouchedAndReleasesTheRoom(ErrorCode error) throws Exception {
        var before = room();
        when(classifier.classify(QUESTION)).thenThrow(new BusinessException(error, "잠시 후 다시 시도해 주세요."));
        var result = send(sessionId, QUESTION, 503);
        assertThat(body(result).path("code").asString()).isEqualTo(error.getCode());
        assertThat(body(result).has("questionMessageId")).isFalse();
        assertThat(count()).isZero();
        assertThat(room()).isEqualTo(before);
        doReturn(QuestionCategory.PASS).when(classifier).classify(QUESTION);
        send(sessionId, QUESTION, 200);
    }

    @Test
    void ownerAndProfileChecksStopBeforeAiIncludingInconsistentFixtures() throws Exception {
        long otherMember = member();
        mvc.perform(post("/api/chat-sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(QUESTION)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat-sessions/{id}/messages", sessionId).header("Authorization", authorization(otherMember))
                        .contentType(MediaType.APPLICATION_JSON).content(json(QUESTION)))
                .andExpect(status().isForbidden());
        send(Long.MAX_VALUE, QUESTION, 404);
        long inconsistent = session(otherMember, profileId);
        mvc.perform(post("/api/chat-sessions/{id}/messages", inconsistent).header("Authorization", authorization(otherMember))
                        .contentType(MediaType.APPLICATION_JSON).content(json(QUESTION)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE chat_session SET profile_id=NULL WHERE session_id=?", sessionId);
        send(sessionId, QUESTION, 404);
        verifyNoInteractions(classifier);
        assertThat(count()).isZero();
    }

    @Test
    void ownershipIsRecheckedAfterClassification() throws Exception {
        long otherMember = member();
        when(classifier.classify(QUESTION)).thenAnswer(invocation -> {
            jdbc.update("UPDATE profile SET member_id=? WHERE profile_id=?", otherMember, profileId);
            return QuestionCategory.PASS;
        });
        var before = room();
        send(sessionId, QUESTION, 403);
        assertThat(count()).isZero();
        assertThat(room()).isEqualTo(before);
    }

    @Test
    void removedRoomDuringClassificationCannotBeSaved() throws Exception {
        when(classifier.classify(QUESTION)).thenAnswer(invocation -> {
            jdbc.update("DELETE FROM chat_session WHERE session_id=?", sessionId);
            return QuestionCategory.PASS;
        });
        send(sessionId, QUESTION, 404);
        assertThat(count()).isZero();
    }

    @Test
    void failedInsertRollsBackSnapshotTitleAndActivityAndAllowsTheNextRequest() throws Exception {
        jdbc.update("UPDATE profile SET profile_pregnant=TRUE WHERE profile_id=?", profileId);
        var before = room();
        // 첫 저장만 실패시킨다. 이후 호출은 Spring Data 저장소의 원래 응답 방식으로 복귀한다.
        var originalSave = mockingDetails(messages).getMockCreationSettings().getDefaultAnswer();
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false)) throw new DataIntegrityViolationException("synthetic write failure");
            return originalSave.answer(invocation);
        }).when(messages).save(any());
        send(sessionId, QUESTION, 500);
        assertThat(count()).isZero();
        assertThat(room()).isEqualTo(before);
        send(sessionId, QUESTION, 200);
        assertThat(room()).containsEntry("snap_pregnant", true);
    }

    @Test
    void sameRoomOverlapsReturn409WhileOtherRoomsAndProfileUpdatesContinue() throws Exception {
        long anotherRoom = session(memberId, profileId);
        CountDownLatch classifying = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(classifier.classify(QUESTION)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            classifying.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return QuestionCategory.PASS;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> send(sessionId, QUESTION, 200));
            try {
                assertThat(classifying.await(10, TimeUnit.SECONDS)).isTrue();
                var duplicate = send(sessionId, QUESTION, 409);
                assertThat(body(duplicate).path("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
                // 첫 요청이 외부 대기 중이어도 같은 프로필의 다른 방과 프로필 수정이 잠기지 않는다.
                jdbc.update("UPDATE profile SET profile_pregnant=TRUE WHERE profile_id=?", profileId);
                send(anotherRoom, "다른 방 질문", 200);
            } finally {
                finish.countDown();
            }
            first.get(15, TimeUnit.SECONDS);
        }
        verify(classifier, times(1)).classify(QUESTION);
        assertThat(count()).isEqualTo(1);
        assertThat(room()).containsEntry("snap_pregnant", true);
        send(sessionId, "완료 후 다음 질문", 200);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void databaseLocksSerializeWritersEvenWhenCallingTheStoreDirectly() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return store.save(sessionId, memberId, profileId, QUESTION).turnNo();
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 2);
        }
        assertThat(count()).isEqualTo(2);
    }

    private MvcResult send(long session, String question, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/chat-sessions/{id}/messages", session).header("Authorization", authorization(memberId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(question)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private String json(String question) { return JSON.writeValueAsString(Map.of("content", question)); }
    private JsonNode body(MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsString()); }
    private Map<String, Object> room() { return jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?", sessionId); }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?", Long.class, sessionId); }
    private String authorization(long member) { return "Bearer " + tokens.createAccessToken(member, "fixture@example.test", "ROLE_USER"); }

    private long session(long member, long profile) {
        return insert("INSERT INTO chat_session(member_id,profile_id,title,created_at,updated_at,snap_birth_year,snap_gender,snap_pregnant) "
                + "VALUES(?, ?, NULL, '2020-01-02 03:04:05', '2020-01-02 03:04:05', 2000, 'FEMALE', FALSE)", member, profile);
    }

    private long member() {
        long member = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?, 'fixture-only', 'fixture', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "chat004-" + UUID.randomUUID() + "@example.test");
        members.add(member);
        return member;
    }

    private long insert(String sql, Object... arguments) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
