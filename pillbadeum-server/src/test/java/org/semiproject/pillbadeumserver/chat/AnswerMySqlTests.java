package org.semiproject.pillbadeumserver.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.semiproject.pillbadeumserver.chat.service.AnswerFailedException;
import org.semiproject.pillbadeumserver.chat.service.AnswerSources;
import org.semiproject.pillbadeumserver.chat.service.ChatAnswerService;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.GeneratedAnswer;
import org.semiproject.pillbadeumserver.chat.service.OfficialDrugEvidence;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.service.DurLookup;
import org.semiproject.pillbadeumserver.drug.domain.DurWarningRepository;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.chat.domain.ChatMessageRepository;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실제 필터·분류/공식 정보/Responses HTTP·Service·MySQL 커밋을 한 요청으로 검증한다.
 * 외부 주소와 키는 테스트가 시작한 로컬 서버/공개 합성 값으로 덮어쓰며 실제 유료 호출은 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class AnswerMySqlTests {
    private static final JsonMapper JSON = new JsonMapper();
    private static HttpServer server;
    private static ExecutorService httpThreads;
    private static final AtomicInteger decisions = new AtomicInteger();
    private static final AtomicInteger generations = new AtomicInteger();
    private static final AtomicInteger publicCalls = new AtomicInteger();
    private static final AtomicReference<String> category = new AtomicReference<>("PASS");
    private static final AtomicInteger decisionStatus = new AtomicInteger(200);
    private static final AtomicInteger generationStatus = new AtomicInteger(200);
    private static final AtomicInteger publicStatus = new AtomicInteger(200);
    private static final AtomicReference<String> response = new AtomicReference<>();
    private static final AtomicReference<Runnable> duringGeneration = new AtomicReference<>(() -> {});
    private static final List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<String> publicCodes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<String, String> durBodies = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Integer> durStatuses = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<String> durCodes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final AtomicReference<Runnable> duringDur = new AtomicReference<>(() -> {});
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired QuestionStore store;
    @Autowired ChatAnswerService answers;
    @Autowired AnswerSources sources;
    @MockitoSpyBean OpenAiClient openAi;
    @MockitoSpyBean ChatMessageRepository messages;
    @MockitoSpyBean DurWarningRepository warnings;
    @MockitoSpyBean PublicDataClient publicData;
    @MockitoSpyBean org.semiproject.pillbadeumserver.chat.service.MessageEmbeddingService embeddings;
    private final List<Long> members = new ArrayList<>();
    private final List<String> drugs = new ArrayList<>();
    private long memberId;
    private long profileId;
    private long sessionId;
    private String drugCode;

    @DynamicPropertySource
    static void localProviders(DynamicPropertyRegistry registry) {
        registry.add("openai.base-url", AnswerMySqlTests::baseUrl);
        registry.add("openai.api-key", () -> "synthetic-ai-test-key");
        registry.add("openai.model.chat", () -> "synthetic-chat");
        registry.add("openai.model.decision", () -> "synthetic-decision");
        registry.add("public-data.base-url", AnswerMySqlTests::baseUrl);
        registry.add("public-data.service-key", () -> "synthetic-public-test-key");
    }

    private static synchronized String baseUrl() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                httpThreads = Executors.newVirtualThreadPerTaskExecutor();
                server.setExecutor(httpThreads);
                server.createContext("/", AnswerMySqlTests::handle);
                server.start();
            } catch (IOException exception) { throw new IllegalStateException(exception); }
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void prepareOwnedSyntheticDataAndResetProviderResponses() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var connection = dataSource.getConnection()) {
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }
        decisions.set(0); generations.set(0); publicCalls.set(0);
        requests.clear(); publicCodes.clear(); category.set("PASS");
        durBodies.clear(); durStatuses.clear(); durCodes.clear(); duringDur.set(() -> {});
        decisionStatus.set(200); generationStatus.set(200); publicStatus.set(200);
        response.set(completed("합성 공식 자료에 따른 안내\n@@SOURCES@@\n[{\"source\":\"fabricated\"}]"));
        duringGeneration.set(() -> {});
        memberId = member(); profileId = profile(memberId, true); sessionId = session(profileId);
        drugCode = medication(profileId, "합성등록약정");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(openAi).createTextResult(anyString(), anyList(), anyInt());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(publicData).get(anyString(), anyMap());
    }

    @AfterEach
    void cleanOnlyThisTestsData() {
        for (long member : members) {
            jdbc.update("DELETE m FROM chat_message m JOIN chat_session s ON s.session_id=m.session_id WHERE s.member_id=?", member);
            jdbc.update("DELETE FROM chat_session WHERE member_id=?", member);
        }
        for (long member : members) {
            jdbc.update("DELETE m FROM my_drug m JOIN profile p ON p.profile_id=m.profile_id WHERE p.member_id=?", member);
            jdbc.update("DELETE FROM profile WHERE member_id=?", member);
            jdbc.update("DELETE FROM member WHERE member_id=?", member);
        }
        for (String code : drugs) {
            jdbc.update("DELETE FROM dur_warning WHERE item_seq=?", code);
            jdbc.update("DELETE FROM dur_interaction WHERE item_seq2=?", code);
            jdbc.update("DELETE FROM drug WHERE item_seq=?", code);
        }
        members.clear(); drugs.clear();
    }

    @AfterAll
    static void stopLocalServer() {
        if (server != null) server.stop(0);
        if (httpThreads != null) httpThreads.shutdownNow();
    }

    @Test
    void aCompleteRequestCommitsOneQuestionAndOneAnswerWithServerSourcesAndKst(CapturedOutput output) throws Exception {
        var result = send(sessionId, "복약 질문", 200);
        var body = body(result);
        assertThat(body.path("result").asString()).isEqualTo("ANSWERED");
        long question = body.path("question").path("messageId").asLong();
        long answer = body.path("answer").path("messageId").asLong();
        var rows = jdbc.queryForList("SELECT * FROM chat_message WHERE session_id=? ORDER BY message_id", sessionId);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).containsEntry("message_id", question).containsEntry("role", "user").containsEntry("trun_no", 1).containsEntry("token_count", null);
        assertThat(rows.getLast()).containsEntry("message_id", answer).containsEntry("role", "assistant").containsEntry("trun_no", 1).containsEntry("token_count", null);
        assertThat(rows.getLast().get("content")).isEqualTo(body.path("answer").path("content").asString());
        assertThat(body.path("answer").path("createdAt").asString()).endsWith("+09:00");
        assertThat(body.path("answer").path("durNotice").isNull()).isTrue();
        assertThat(room()).containsEntry("snap_pregnant", true).containsEntry("last_response_id", "resp-local-fixture");
        assertThat(sources.sources(body.path("answer").path("content").asString())).hasSize(4)
                .extracting(AnswerSource::source).containsExactly(AnswerSource.Type.EASY_DRUG, AnswerSource.Type.PERMIT, AnswerSource.Type.USER_INPUT, AnswerSource.Type.DUR);
        assertThat(body.path("answer").path("content").asString()).doesNotContain("fabricated");
        assertThat(decisions.get()).isEqualTo(1); assertThat(generations.get()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("member-private-marker", "개인표식", "synthetic-ai-test-key", "synthetic-public-test-key", "fabricated");
    }

    @Test
    void currentProfileChangesDoNotChangeAnEstablishedSnapshotAndNewRoomsUseTheNewValue() throws Exception {
        duringGeneration.set(() -> jdbc.update("UPDATE profile SET profile_pregnant=FALSE WHERE profile_id=?", profileId));
        send(sessionId, "첫 질문", 200);
        send(sessionId, "다음 질문", 200);
        assertThat(requests).hasSize(2);
        assertThat(background(requests.get(0)).path("patient").path("pregnant").asString()).isEqualTo("YES");
        assertThat(background(requests.get(1)).path("patient").path("pregnant").asString()).isEqualTo("YES");
        long nextRoom = session(profileId);
        send(nextRoom, "새 방 질문", 200);
        assertThat(background(requests.get(2)).path("patient").path("pregnant").asString()).isEqualTo("NO");
        assertThat(room()).containsEntry("snap_pregnant", true);
    }

    @Test
    void onlyThisRoomsCompletedTurnsAndThisProfilesMedicationEnterThePrompt() throws Exception {
        long otherProfile = profile(memberId, false);
        String otherDrug = medication(otherProfile, "다른프로필약정");
        long otherRoom = session(otherProfile);
        pair(otherRoom, 1, "other-room-question", "other-room-answer");
        pair(sessionId, 1, "own-one", "own-answer-one\n@@SOURCES@@\n[private-old-source]");
        jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,2,'user','unanswered-old-question')", sessionId);
        pair(sessionId, 3, "own-three", "own-answer-three");
        send(sessionId, "지금 질문", 200);
        String input = requests.getFirst().path("input").toString();
        assertThat(input).contains("own-one", "own-three", drugCode)
                .doesNotContain("other-room", "unanswered-old-question", "private-old-source", "@@SOURCES@@", otherDrug,
                        "member-private-marker", "개인표식", "profile_height", "profile_weight");
        assertThat(publicCodes).containsOnly(drugCode);
        assertThat(jdbc.queryForObject("SELECT MAX(trun_no) FROM chat_message WHERE session_id=?", Integer.class, sessionId)).isEqualTo(4);
    }

    @Test
    void legacyNullPregnancyRemainsUnknownAndNoMedicationTablesAreChanged() throws Exception {
        pair(sessionId, 1, "legacy-user", "legacy-answer");
        jdbc.update("UPDATE chat_session SET snap_pregnant=NULL WHERE session_id=?", sessionId);
        var before = jdbc.queryForList("SELECT * FROM my_drug WHERE profile_id=?", profileId);
        var body = body(send(sessionId, "다음 복약 질문", 200));
        assertThat(background(requests.getFirst()).path("patient").path("pregnant").asString()).isEqualTo("UNKNOWN");
        assertThat(room().get("snap_pregnant")).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM my_drug WHERE profile_id=?", profileId)).isEqualTo(before);
        assertThat(body.path("answer").path("durNotice").path("lookup").asString()).isEqualTo("FAILED");
        assertThat(body.path("answer").path("durNotice").path("result").isNull()).isTrue();
    }

    @Test
    void repeatedGeneralGuidanceOmitsTheCardButRetainsDurContextAndBodyWarning() throws Exception {
        pair(sessionId, 1, "legacy-user", "legacy-answer");
        var result = body(send(sessionId, "알림 설정은 어디에 있어?", 200));
        assertThat(result.path("answer").path("durNotice").isNull()).isTrue();
        assertThat(background(requests.getFirst()).path("dur").path("lookup").asString()).isEqualTo("FAILED");
        assertThat(result.path("answer").path("content").asString()).contains("DUR 확인:", "금기가 없다는 뜻은 아니니");
    }

    @Test
    void aGenerationFailureKeepsTheQuestionAndTheNextPostUsesANewTurn() throws Exception {
        generationStatus.set(503);
        var failed = body(send(sessionId, "실패할 질문", 503));
        long questionId = failed.path("questionMessageId").asLong();
        assertThat(questionId).isPositive();
        assertThat(failed.path("code").asString()).isEqualTo("EXTERNAL_API_FAILED");
        assertThat(failed.path("message").asString()).isEqualTo("답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT role FROM chat_message WHERE message_id=?", String.class, questionId)).isEqualTo("user");
        generationStatus.set(200);
        var next = body(send(sessionId, "다음 질문", 200));
        assertThat(next.path("question").path("turnNo").asInt()).isEqualTo(2);
        assertThat(count()).isEqualTo(3);
        assertThat(requests.getLast().path("input").toString()).doesNotContain("실패할 질문");
    }

    @Test
    void blockedAndFailedClassificationDoNotCreateQuestionIdsOrTouchAnyRoomColumn() throws Exception {
        var before = room();
        category.set("OFF_TOPIC");
        send(sessionId, "차단 질문", 200);
        assertThat(room()).isEqualTo(before); assertThat(count()).isZero();
        assertThat(generations.get()).isZero(); assertThat(publicCalls.get()).isZero();
        decisionStatus.set(503);
        var failure = body(send(sessionId, "분류 실패 질문", 503));
        assertThat(failure.has("questionMessageId")).isFalse();
        assertThat(room()).isEqualTo(before); assertThat(count()).isZero();
    }

    @Test
    void noMedicationAndUnavailableOfficialDataProduceStoredFallbackAnswersWithoutOpenAiGeneration() throws Exception {
        jdbc.update("DELETE FROM my_drug WHERE profile_id=?", profileId);
        var empty = body(send(sessionId, "복약 질문", 200));
        assertThat(empty.path("answer").path("content").asString()).contains("약을 등록하면");
        assertThat(generations.get()).isZero(); assertThat(publicCalls.get()).isZero();
        medication(profileId, "다시등록한약정");
        publicStatus.set(400);
        var unavailable = body(send(sessionId, "등록 후 질문", 200));
        assertThat(unavailable.path("answer").path("content").asString()).contains("공식 정보를 확인하지 못해");
        assertThat(generations.get()).isZero();
        assertThat(count()).isEqualTo(4);
    }

    @Test
    void badStoredScheduleFailsAfterQuestionCommitWithItsId() throws Exception {
        jdbc.update("UPDATE my_drug SET dose_times='invalid-fixture-time' WHERE profile_id=?", profileId);
        var error = body(send(sessionId, "질문", 503));
        assertThat(error.path("questionMessageId").asLong()).isPositive();
        assertThat(count()).isEqualTo(1); assertThat(generations.get()).isZero();
    }

    @Test
    void anAnswerCommitFailureRollsBackAnswerAndSessionUpdateButNotTheQuestion() throws Exception {
        AtomicReference<Map<String, Object>> beforeAnswer = new AtomicReference<>();
        duringGeneration.set(() -> beforeAnswer.set(room()));
        var original = mockingDetails(messages).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            var entity = (org.semiproject.pillbadeumserver.chat.domain.ChatMessage) invocation.getArgument(0);
            Object saved = original.answer(invocation);
            if ("assistant".equals(entity.getRole())) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { throw new DataIntegrityViolationException("synthetic commit failure"); }
                });
            }
            return saved;
        }).when(messages).save(any());
        var error = body(send(sessionId, "커밋 실패 질문", 503));
        assertThat(error.path("questionMessageId").asLong()).isPositive();
        assertThat(count()).isEqualTo(1);
        assertThat(room()).isEqualTo(beforeAnswer.get());
    }

    @Test
    void anExistingAssistantIsNotInsertedAgainOrOverwritten() throws Exception {
        var result = body(send(sessionId, "질문", 200));
        var question = new org.semiproject.pillbadeumserver.chat.dto.QuestionResponse.Question(
                result.path("question").path("messageId").asLong(), result.path("question").path("turnNo").asInt());
        var before = jdbc.queryForList("SELECT * FROM chat_message WHERE session_id=? ORDER BY message_id", sessionId);
        var failure = assertThrows(BusinessException.class, () -> store.saveAnswer(sessionId, memberId, profileId, question,
                new GeneratedAnswer("must not replace", "other-response", null), ChatDeadline.after(Duration.ofSeconds(5))));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_REQUEST);
        assertThat(jdbc.queryForList("SELECT * FROM chat_message WHERE session_id=? ORDER BY message_id", sessionId)).isEqualTo(before);
    }

    @Test
    void ownershipIsCheckedAgainAfterTheModelResponse() throws Exception {
        long otherMember = member();
        duringGeneration.set(() -> jdbc.update("UPDATE profile SET member_id=? WHERE profile_id=?", otherMember, profileId));
        send(sessionId, "질문", 403);
        assertThat(count()).isEqualTo(1);
        assertThat(room().get("last_response_id")).isNull();
    }

    @Test
    void generationOverlapReturns409WhileAnotherRoomAndProfileUpdateContinue() throws Exception {
        long anotherRoom = session(profileId);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger arrivals = new AtomicInteger();
        duringGeneration.set(() -> {
            if (arrivals.incrementAndGet() != 1) return;
            entered.countDown(); await(release);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> send(sessionId, "느린 질문", 200));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var overlapping = body(send(sessionId, "겹친 질문", 409));
                assertThat(overlapping.path("code").asString()).isEqualTo("REQUEST_IN_PROGRESS");
                jdbc.update("UPDATE profile SET profile_pregnant=FALSE WHERE profile_id=?", profileId);
                send(anotherRoom, "다른 방 질문", 200);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(count()).isEqualTo(2);
        assertThat(decisions.get()).isEqualTo(2);
    }

    @Test
    void anExpiredDeadlineCannotWriteAnAnswerEvenWhenTheProviderFinishesLater() throws Exception {
        var question = store.save(sessionId, memberId, profileId, "늦은 답변 질문");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), responded = new CountDownLatch(1);
        duringGeneration.set(() -> { entered.countDown(); await(release); responded.countDown(); });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var failure = executor.submit(() -> assertThrows(AnswerFailedException.class,
                    () -> answers.complete(sessionId, memberId, profileId, question, ChatDeadline.after(Duration.ofMillis(500)))));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var error = failure.get(3, TimeUnit.SECONDS);
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_TIMEOUT);
                assertThat(error.getQuestionMessageId()).isEqualTo(question.messageId());
                assertThat(count()).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(responded.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count()).isEqualTo(1);
        assertThat(room().get("last_response_id")).isNull();
    }

    @Test
    void firstAnswerIncludesAPairOutsideTheOfficialEvidenceSelectionAndCachesItemSeq2() throws Exception {
        String partner = medication(profileId, "다른합성약정");
        durZero(drugCode); durZero(partner);
        durRows(drugCode, DurLookup.Type.INTERACTION, List.of(Map.of("ITEM_SEQ", drugCode,
                "MIXTURE_ITEM_SEQ", partner, "PROHBT_CONTENT", "synthetic pair warning", "CHANGE_DATE", "20261017")));
        var result = body(send(sessionId, "합성등록약정은 같이 먹어도 돼?", 200));
        var notice = result.path("answer").path("durNotice");
        assertThat(notice.path("lookup").asString()).isEqualTo("ALL");
        assertThat(notice.path("result").asString()).isEqualTo("FOUND");
        assertThat(notice.path("checkedCount").asInt()).isEqualTo(2);
        assertThat(notice.path("items").toString()).contains("합성등록약정", "다른합성약정", "synthetic pair warning");
        assertThat(notice.path("items").path(0).path("type").asString()).isEqualTo("INTERACTION");
        assertThat(result.path("answer").path("content").asString()).contains("병용금기 ·");
        assertThat(publicCodes).containsOnly(drugCode);
        assertThat(durCodes).contains(drugCode, partner);
        assertThat(background(requests.getFirst()).path("dur").path("result").asString()).isEqualTo("FOUND");
        assertThat(jdbc.queryForObject("SELECT mixture_item_seq FROM dur_interaction WHERE item_seq2=?", String.class, drugCode)).isEqualTo(partner);
        assertThat(jdbc.queryForObject("SELECT content FROM dur_interaction WHERE item_seq2=?", String.class, drugCode)).isEqualTo("synthetic pair warning");
        assertThat(sources.sources(result.path("answer").path("content").asString()).stream()
                .filter(s -> s.source() == AnswerSource.Type.DUR)).hasSize(2).allMatch(AnswerSource::ok);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void successfulZeroClearsOnlyItsTypeAndFailedOrUnrequestedCacheTypesRemain() throws Exception {
        seedWarning("연령", "old age");seedWarning("임부", "old pregnancy");seedWarning("용량", "old dose");
        jdbc.update("INSERT INTO dur_interaction(item_seq2,mixture_item_seq,content,synced_at) VALUES(?,NULL,'old unrequested pair',CURRENT_TIMESTAMP)", drugCode);
        durRows(drugCode, DurLookup.Type.AGE, List.of());
        durStatuses.put(durKey(drugCode, DurLookup.Type.PREGNANCY), 403);
        var result = body(send(sessionId, "복약 질문", 200));
        assertThat(background(requests.getFirst()).path("dur").path("lookup").asString()).isEqualTo("PARTIAL");
        assertThat(result.path("answer").path("content").asString()).contains("일부 약·유형은 확인하지 못했어요");
        assertThat(jdbc.queryForList("SELECT content FROM dur_warning WHERE item_seq=? ORDER BY content", String.class, drugCode))
                .containsExactly("old dose", "old pregnancy");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dur_interaction WHERE item_seq2=?", Integer.class, drugCode)).isEqualTo(1);
        assertThat(sources.sources(result.path("answer").path("content").asString()).getLast().ok()).isFalse();
    }

    @Test
    void aLegacyNullSnapshotProducesConditionalPregnancyGuidanceAndKeepsTheProfile() throws Exception {
        pair(sessionId, 1, "previous", "answer");
        jdbc.update("UPDATE chat_session SET snap_pregnant=NULL WHERE session_id=?", sessionId);
        durZero(drugCode);
        durRows(drugCode, DurLookup.Type.PREGNANCY, List.of(Map.of("ITEM_SEQ", drugCode,
                "PROHBT_CONTENT", "synthetic pregnancy caution", "INGR_NAME", "fixture", "CHANGE_DATE", "20261017")));
        var result = body(send(sessionId, "복약 질문", 200));
        assertThat(result.path("answer").path("durNotice").path("result").asString()).isEqualTo("FOUND");
        assertThat(result.path("answer").path("content").asString()).contains("임신 중이라면 주의가 필요해요");
        assertThat(room().get("snap_pregnant")).isNull();
        assertThat(jdbc.queryForObject("SELECT profile_pregnant FROM profile WHERE profile_id=?", Boolean.class, profileId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT type FROM dur_warning WHERE item_seq=?", String.class, drugCode)).isEqualTo("임부");
    }

    @Test
    void commitTimeCacheFailureRollsBackItsDeletesAndDoesNotDiscardTheAnswer() throws Exception {
        seedWarning("연령", "preserve after cache rollback");
        durZero(drugCode);
        durRows(drugCode, DurLookup.Type.PREGNANCY, List.of(Map.of("ITEM_SEQ", drugCode, "PROHBT_CONTENT", "new pregnancy caution")));
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new DataIntegrityViolationException("synthetic cache commit failure"); }
            });
            return invocation.callRealMethod();
        }).when(warnings).save(any());
        var result = body(send(sessionId, "복약 질문", 200));
        assertThat(result.path("result").asString()).isEqualTo("ANSWERED");
        assertThat(result.path("answer").path("durNotice").path("result").asString()).isEqualTo("FOUND");
        assertThat(jdbc.queryForList("SELECT content FROM dur_warning WHERE item_seq=?", String.class, drugCode))
                .containsExactly("preserve after cache rollback");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void longOfficialPairContentIsShownWholeButDoesNotOverwriteALimitedCacheColumn() throws Exception {
        String partner = medication(profileId, "다른합성약정");
        jdbc.update("INSERT INTO dur_interaction(item_seq2,mixture_item_seq,content,synced_at) VALUES(?,?,'old short cache',CURRENT_TIMESTAMP)", drugCode, partner);
        durZero(drugCode);durZero(partner);
        String longContent = "synthetic-" + "x".repeat(300);
        durRows(drugCode, DurLookup.Type.INTERACTION, List.of(Map.of("ITEM_SEQ", drugCode,
                "MIXTURE_ITEM_SEQ", partner, "PROHBT_CONTENT", longContent)));
        var result = body(send(sessionId, "같이 먹어도 돼?", 200));
        assertThat(result.path("answer").path("content").asString()).contains(longContent);
        assertThat(jdbc.queryForObject("SELECT content FROM dur_interaction WHERE item_seq2=?", String.class, drugCode)).isEqualTo("old short cache");
    }

    @Test
    void lateDurResponseCannotRefreshACacheOrStoreAnAnswerAfterTheDeadline() throws Exception {
        seedWarning("연령", "preserve after late DUR");
        durZero(drugCode);
        var question = store.save(sessionId, memberId, profileId, "느린 DUR 질문");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), responded = new CountDownLatch(1);
        duringDur.set(() -> { entered.countDown(); await(release);responded.countDown(); });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var failure = executor.submit(() -> assertThrows(AnswerFailedException.class,
                    () -> answers.complete(sessionId, memberId, profileId, question, ChatDeadline.after(Duration.ofMillis(500)))));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get(3, TimeUnit.SECONDS).getQuestionMessageId()).isEqualTo(question.messageId());
            } finally { release.countDown(); }
            assertThat(responded.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT content FROM dur_warning WHERE item_seq=?", String.class, drugCode))
                .containsExactly("preserve after late DUR");
    }

    private void seedWarning(String type, String content) {
        jdbc.update("INSERT INTO dur_warning(item_seq,type,content,synced_at) VALUES(?,?,?,CURRENT_TIMESTAMP)", drugCode, type, content);
    }

    private void durZero(String code) { for (var type : DurLookup.Type.values()) durRows(code, type, List.of()); }
    private void durRows(String code, DurLookup.Type type, List<?> rows) {
        durBodies.put(durKey(code, type), JSON.writeValueAsString(Map.of("header", Map.of("resultCode", "00"),
                "body", Map.of("totalCount", rows.size(), "pageNo", 1, "items", rows))));
    }
    private static String durKey(String code, DurLookup.Type type) { return code + ":" + type; }

    @Test
    void retryRecoversTheSameQuestionAndDoesNotClassifyInsertOrReembedIt() throws Exception {
        generationStatus.set(503);
        long question = body(send(sessionId, "복약 질문", 503)).path("questionMessageId").asLong();
        var state = room();
        var failure = body(retry(question, 503));
        assertThat(failure.path("questionMessageId").asLong()).isEqualTo(question);
        assertThat(count()).isEqualTo(1);
        assertThat(room()).isEqualTo(state);
        generationStatus.set(200);
        var result = body(retry(question, 200));
        assertThat(result.path("question").path("messageId").asLong()).isEqualTo(question);
        assertThat(result.path("answer").path("turnNo").asInt()).isEqualTo(1);
        assertThat(count()).isEqualTo(2);
        assertThat(decisions.get()).isEqualTo(1);
        assertThat(generations.get()).isEqualTo(3);
        org.mockito.Mockito.verify(embeddings, org.mockito.Mockito.times(1)).questionCommitted(eq(sessionId), eq(question), anyString(), any());
        retry(question, 400).getResponse();
        assertThat(count()).isEqualTo(2);
        assertThat(generations.get()).isEqualTo(3);
    }

    @Test
    void retryRejectsOldQuestionAssistantMissingAndForeignRoomBeforeGeneration() throws Exception {
        generationStatus.set(503);
        long old = body(send(sessionId, "첫 복약 질문", 503)).path("questionMessageId").asLong();
        generationStatus.set(200);
        var latest = body(send(sessionId, "새 복약 질문", 200));
        long question = latest.path("question").path("messageId").asLong();
        long answer = latest.path("answer").path("messageId").asLong();
        for (long id : new long[]{old, question, answer}) {
            assertThat(body(retry(id, 400)).path("code").asString()).isEqualTo("RETRY_NOT_ALLOWED");
        }
        retry(Long.MAX_VALUE, 404);
        long foreign = session(profile(member(), false));
        mvc.perform(post("/api/chat-sessions/{s}/messages/{q}/retry", foreign, old)
                .header("Authorization", auth())).andExpect(status().isForbidden());
        assertThat(generations.get()).isEqualTo(2);
    }

    @Test
    void generatingNewQuestionHidesRetryAndRejectsBothCompetingEndpoints() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        duringGeneration.set(() -> {entered.countDown(); try {release.await(5, TimeUnit.SECONDS);} catch (InterruptedException e) {Thread.currentThread().interrupt();}});
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = tasks.submit(() -> send(sessionId, "복약 질문", 200));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                long question = jdbc.queryForObject("SELECT MAX(message_id) FROM chat_message WHERE session_id=?", Long.class, sessionId);
                mvc.perform(get("/api/chat-sessions/{s}/messages", sessionId).header("Authorization", auth()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.retryMessageId").isEmpty());
                retry(question, 409);
                send(sessionId, "다른 복약 질문", 409);
            } finally {release.countDown();}
            request.get(3, TimeUnit.SECONDS);
        }
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void retrySharesTheGuardWithNewQuestionsAndHidesItsOwnRetryMarker() throws Exception {
        generationStatus.set(503);
        long question = body(send(sessionId, "복약 질문", 503)).path("questionMessageId").asLong();
        generationStatus.set(200);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        duringGeneration.set(() -> {entered.countDown(); try {release.await(5, TimeUnit.SECONDS);} catch (InterruptedException e) {Thread.currentThread().interrupt();}});
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = tasks.submit(() -> retry(question, 200));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                retry(question, 409); send(sessionId, "새 복약 질문", 409);
                mvc.perform(get("/api/chat-sessions/{s}/messages", sessionId).header("Authorization", auth()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.retryMessageId").isEmpty());
            } finally {release.countDown();}
            request.get(3, TimeUnit.SECONDS);
        }
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void retryRechecksTheLatestQuestionBeforeSavingAnAnswer() throws Exception {
        generationStatus.set(503);
        long question = body(send(sessionId, "복약 질문", 503)).path("questionMessageId").asLong();
        generationStatus.set(200);
        duringGeneration.set(() -> jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,2,'user','external-writer')", sessionId));
        assertThat(body(retry(question, 400)).path("code").asString()).isEqualTo("RETRY_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=? AND role='assistant'", Integer.class, sessionId)).isZero();
    }

    private String auth() { return "Bearer " + tokens.createAccessToken(memberId, "fixture@example.test", "ROLE_USER"); }
    private MvcResult retry(long question, int expected) throws Exception {
        return mvc.perform(post("/api/chat-sessions/{s}/messages/{q}/retry", sessionId, question)
                .header("Authorization", auth())).andExpect(status().is(expected)).andReturn();
    }

    private MvcResult send(long session, String content, int status) throws Exception {
        return mvc.perform(post("/api/chat-sessions/{id}/messages", session)
                        .header("Authorization", "Bearer " + tokens.createAccessToken(memberId, "fixture@example.test", "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("content", content))))
                .andExpect(status().is(status)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsString()); }
    private JsonNode background(JsonNode request) { return JSON.readTree(request.path("input").path(0).path("content").asString().split("\n", 2)[1]); }
    private Map<String, Object> room() { return jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?", sessionId); }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?", Long.class, sessionId); }
    private void pair(long session, int turn, String question, String answer) {
        jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,?,'user',?),(?,?,'assistant',?)", session, turn, question, session, turn, answer);
    }

    private long member() {
        long id = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) VALUES(?, 'fixture', 'member-private-marker', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "answer-mysql-" + UUID.randomUUID() + "@example.test");
        members.add(id); return id;
    }

    private long profile(long member, boolean pregnant) {
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_height,profile_weight,profile_pregnant,created_at,updated_at) "
                + "VALUES(?, '개인표식', 'SELF', 2000, 'FEMALE', 177, 73, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", member, pregnant);
    }

    private long session(long profile) {
        return insert("INSERT INTO chat_session(member_id,profile_id,created_at,updated_at,snap_birth_year,snap_gender,snap_pregnant) VALUES(?,?, '2020-01-02 03:04:05','2020-01-02 03:04:05',2000,'FEMALE',FALSE)", memberId, profile);
    }

    private String medication(long profile, String name) {
        String code = "answer-" + UUID.randomUUID();
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) VALUES(?,?,'fixture-maker','OTC','fixture','fixture','fixture','TABLET','fixture',CURRENT_TIMESTAMP)", code, name);
        drugs.add(code);
        insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,timing,start_date,end_date,dose_times,source) VALUES(?,?,1,'user-timing',?,?,'07:00,17:00','SEARCH')", profile, code,
                LocalDate.now(TimeZoneConfig.ZONE), LocalDate.now(TimeZoneConfig.ZONE).plusDays(1));
        return code;
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/decisions")) {
            decisions.incrementAndGet();
            respond(exchange, decisionStatus.get(), JSON.writeValueAsString(Map.of("answers", List.of(Map.of(
                    "type", "choice", "name", "medication_category", "choice", category.get())))));
        } else if (path.equals("/responses")) {
            generations.incrementAndGet();
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            duringGeneration.get().run();
            try { respond(exchange, generationStatus.get(), response.get()); } catch (IOException ignored) { exchange.close(); }
        } else if (path.equals(OfficialDrugEvidence.EASY_PATH) || path.equals(OfficialDrugEvidence.PERMIT_PATH)) {
            publicCalls.incrementAndGet();
            boolean easy = path.equals(OfficialDrugEvidence.EASY_PATH);
            String code = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                    .filter(part -> part.startsWith(easy ? "itemSeq=" : "item_seq="))
                    .map(part -> URLDecoder.decode(part.substring(part.indexOf('=') + 1), StandardCharsets.UTF_8)).findFirst().orElseThrow();
            publicCodes.add(code);
            var item = easy ? Map.of("itemSeq", code, "efcyQesitm", "synthetic official facts", "updateDe", "2026-10-17")
                    : Map.of("ITEM_SEQ", code, "EE_DOC_DATA", "<DOC>synthetic permit facts</DOC>", "CHANGE_DATE", "20261017");
            respond(exchange, publicStatus.get(), JSON.writeValueAsString(Map.of("header", Map.of("resultCode", "00"),
                    "body", Map.of("totalCount", 1, "items", List.of(item)))));
        } else if (path.startsWith("/1471000/DURPrdlstInfoService03/")) {
            String code = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                    .filter(part -> part.startsWith("itemSeq="))
                    .map(part -> URLDecoder.decode(part.substring(part.indexOf('=') + 1), StandardCharsets.UTF_8)).findFirst().orElseThrow();
            var type = java.util.Arrays.stream(DurLookup.Type.values()).filter(t -> t.path().equals(path)).findFirst().orElseThrow();
            durCodes.add(code);
            duringDur.get().run();
            String key = durKey(code, type);
            try { respond(exchange, durStatuses.getOrDefault(key, durBodies.containsKey(key) ? 200 : 404), durBodies.getOrDefault(key, "{}")); }
            catch (IOException ignored) { exchange.close(); }
        } else respond(exchange, 404, "{}");
    }

    private static String completed(String content) {
        return JSON.writeValueAsString(Map.of("id", "resp-local-fixture", "status", "completed", "usage", Map.of("input_tokens", 581, "output_tokens", 99),
                "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", content))))));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
