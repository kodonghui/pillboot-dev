package org.semiproject.pillbadeumserver.chat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.chat.service.*;
import org.semiproject.pillbadeumserver.chat.domain.*;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.TaskDecorator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 답변 커밋·MySQL 구간 저장·Boot 비동기를 검증한다. 생성은 합성 대역이며 실제 API 호출은 없다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@Import(SummaryMySqlTests.Tasks.class)
class SummaryMySqlTests {
    static final AtomicInteger active = new AtomicInteger();
    private static final JsonMapper JSON = new JsonMapper();
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired SummaryService summaries;
    @Autowired QuestionStore questions;
    @MockitoBean QuestionClassifier classifier;
    @MockitoBean AnswerGenerator generator;
    @MockitoSpyBean OpenAiClient openAi;
    @MockitoSpyBean SummaryStore summaryStore;
    private final List<JsonNode> inputs = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private volatile CountDownLatch entered = new CountDownLatch(1);
    private volatile RuntimeException failure;
    private volatile String generated = "합성 요약: 이번 구간의 질문과 안내 내용임.";
    private long memberId, profileId, sessionId;

    @TestConfiguration
    static class Tasks {
        @Bean TaskDecorator summaryTestTasks() {
            return runnable -> {
                active.incrementAndGet();
                return () -> { try { runnable.run(); } finally { active.decrementAndGet(); } };
            };
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var c = dataSource.getConnection()) { org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(c); }
        memberId = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) VALUES(?,'fixture','summary','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "summary-"+UUID.randomUUID()+"@example.test");
        profileId = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) VALUES(?,'summary','SELF',1990,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",memberId);
        sessionId = insert("INSERT INTO chat_session(member_id,profile_id,snap_birth_year,snap_gender,snap_pregnant,created_at,updated_at) VALUES(?,?,1990,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",memberId,profileId);
        when(classifier.classify(anyString())).thenReturn(QuestionCategory.PASS);
        when(generator.generate(any(),any())).thenReturn(new GeneratedAnswer("합성 답변\n@@SOURCES@@\n[]","synthetic-response",null));
        doAnswer(inv -> {
            calls.incrementAndGet();
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<org.semiproject.pillbadeumserver.common.ai.AiMessage> messages = inv.getArgument(1);
            var input = JSON.readTree(messages.getFirst().content()); inputs.add(input);
            assertThat(jdbc.queryForObject("SELECT MAX(trun_no) FROM chat_message WHERE session_id=? AND role='assistant'",Integer.class,sessionId))
                    .isGreaterThanOrEqualTo(input.path("toTurn").asInt());
            entered.countDown(); gate.await(5, TimeUnit.SECONDS);
            if(failure!=null) throw failure;
            return generated;
        }).when(openAi).createText(anyString(),anyList(),anyInt());
    }

    @AfterEach
    void cleanup() throws Exception {
        gate.countDown(); await(() -> active.get()==0);
        jdbc.update("DELETE FROM chat_summary WHERE session_id=?",sessionId);
        jdbc.update("DELETE FROM chat_message WHERE session_id=?",sessionId);
        jdbc.update("DELETE FROM chat_session WHERE session_id=?",sessionId);
        jdbc.update("DELETE FROM profile WHERE profile_id=?",profileId);
        jdbc.update("DELETE FROM member WHERE member_id=?",memberId);
    }

    @Test
    void tenthAnswerCommitsBeforeGenerationAndDoesNotWaitForSummary() throws Exception {
        seedPairs(1,9); gate=new CountDownLatch(1);
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var response=tasks.submit(() -> send(200));
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                response.get(2,TimeUnit.SECONDS);
                assertThat(count()).isZero();
                var before=jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId);
                gate.countDown(); await(() -> active.get()==0);
                assertThat(count()).isEqualTo(1);
                assertThat(jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId)).isEqualTo(before);
            }finally {gate.countDown();}
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?",Integer.class,sessionId)).isEqualTo(20);
        assertThat(inputs.getFirst().path("fromTurn").asInt()).isEqualTo(1);
        assertThat(inputs.getFirst().path("toTurn").asInt()).isEqualTo(10);
        assertThat(inputs.getFirst().toString()).doesNotContain("@@SOURCES@@","private-source");
    }

    @Test
    void lessThanTenAnswersDoesNotGenerateAndTwentiethAddsASecondWindow() throws Exception {
        seedPairs(1,8); send(200); await(() -> active.get()==0); assertThat(calls.get()).isZero();
        send(200); await(() -> active.get()==0); assertThat(count()).isEqualTo(1);
        seedPairs(11,19);send(200);await(() -> active.get()==0);
        assertThat(jdbc.queryForList("SELECT from_turn FROM chat_summary WHERE session_id=? ORDER BY from_turn",Integer.class,sessionId)).containsExactly(1,11);
        assertThat(inputs.get(1).path("previousSummary").asString()).isEqualTo(generated);
    }

    @Test
    void unansweredTurnsRemainExplicitAndDoNotShiftThePhysicalWindow() throws Exception {
        seedPairs(1,8); seedUser(9); send(200); await(() -> active.get()==0);
        assertThat(count()).isEqualTo(1);
        assertThat(inputs.getFirst().path("turns").path(8).path("turnNo").asInt()).isEqualTo(9);
        assertThat(inputs.getFirst().path("turns").path(8).path("answer").isNull()).isTrue();
        assertThat(inputs.getFirst().path("toTurn").asInt()).isEqualTo(10);
    }

    @Test
    void failedBoundaryAnswerWaitsUntilALaterAnswerWithoutInventingTheMissingAnswer() throws Exception {
        seedPairs(1,9);
        when(generator.generate(any(),any())).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_TIMEOUT,"synthetic"));
        send(503);await(() -> active.get()==0);assertThat(count()).isZero();
        doReturn(new GeneratedAnswer("later answer",null,null)).when(generator).generate(any(),any());
        send(200);await(() -> active.get()==0);
        assertThat(count()).isEqualTo(1);
        assertThat(inputs.getFirst().path("turns").path(9).path("answer").isNull()).isTrue();
        assertThat(inputs.getFirst().path("toTurn").asInt()).isEqualTo(10);
    }

    @Test
    void concurrentSubmissionsProduceOneSummaryForTheSameWindow() throws Exception {
        seedPairs(1,10); gate=new CountDownLatch(1);summaries.answerCommitted(sessionId);
        assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        for(int i=0;i<12;i++)summaries.answerCommitted(sessionId);
        gate.countDown();await(() -> active.get()==0);
        assertThat(count()).isEqualTo(1);assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void providerFailureRetriesOnlyAfterTheNextAnswer() throws Exception {
        seedPairs(1,9);failure=new BusinessException(ErrorCode.EXTERNAL_API_TIMEOUT,"private-provider-detail");
        send(200);await(() -> active.get()==0);assertThat(count()).isZero();assertThat(calls.get()).isEqualTo(1);
        failure=null;send(200);await(() -> active.get()==0);
        assertThat(count()).isEqualTo(1);assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void invalidLongSummaryIsNotTruncatedAndSaveFailureRollsBackBeforeNextAnswerRetries() throws Exception {
        seedPairs(1,9);generated="가".repeat(501);send(200);await(() -> active.get()==0);assertThat(count()).isZero();
        generated="유효한 합성 요약";
        doAnswer(inv -> {inv.callRealMethod();throw new DataIntegrityViolationException("synthetic rollback");}).when(summaryStore).save(any(),anyString(),any());
        send(200);await(() -> active.get()==0);assertThat(count()).isZero();
        doCallRealMethod().when(summaryStore).save(any(),anyString(),any());
        send(200);await(() -> active.get()==0);assertThat(count()).isEqualTo(1);
    }

    @Test
    void backlogProcessesAtMostThreeOldestWindowsAndThenContinuesOnTheNextAnswer() throws Exception {
        seedPairs(1,39);send(200);await(() -> active.get()==0);
        assertThat(jdbc.queryForList("SELECT from_turn FROM chat_summary WHERE session_id=? ORDER BY from_turn",Integer.class,sessionId)).containsExactly(1,11,21);
        send(200);await(() -> active.get()==0);
        assertThat(jdbc.queryForList("SELECT from_turn FROM chat_summary WHERE session_id=? ORDER BY from_turn",Integer.class,sessionId)).containsExactly(1,11,21,31);
    }

    @Test
    void legacyRangeSharingAWindowStartDoesNotBlockTheFixedWindows() throws Exception {
        // 02 샘플처럼 10턴 구간이 아닌 1~2 요약이 남아 있는 방이다.
        jdbc.update("INSERT INTO chat_summary(session_id,from_turn,to_turn,content,created_at) VALUES(?,1,2,'legacy-1-2',CURRENT_TIMESTAMP)",sessionId);
        seedPairs(1,19);send(200);await(() -> active.get()==0);
        assertThat(jdbc.queryForList("SELECT CONCAT(from_turn,'-',to_turn) FROM chat_summary WHERE session_id=? ORDER BY from_turn,to_turn",String.class,sessionId))
                .containsExactly("1-2","1-10","11-20");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(inputs.get(1).path("previousSummary").asString()).isEqualTo(generated);
    }

    @Test
    void deletedSessionDuringGenerationCannotLeaveAnOrphanSummary() throws Exception {
        seedPairs(1,10);gate=new CountDownLatch(1);summaries.answerCommitted(sessionId);
        assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM chat_message WHERE session_id=?",sessionId);jdbc.update("DELETE FROM chat_session WHERE session_id=?",sessionId);
        gate.countDown();await(() -> active.get()==0);assertThat(count()).isZero();
    }

    @Test
    void answerInputReadsOnlyTheLatestThreeEarlierSummariesOfTheSameRoomWithoutQdrantOrWrites() {
        seedPairs(1,50);seedUser(51);
        for(int from:new int[]{1,11,21,31,41,61})jdbc.update("INSERT INTO chat_summary(session_id,from_turn,to_turn,content,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",sessionId,from,from+9,"summary-"+from);
        long other=insert("INSERT INTO chat_session(member_id,profile_id,snap_birth_year,snap_gender,snap_pregnant,created_at,updated_at) VALUES(?,?,1990,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",memberId,profileId);
        try {
            jdbc.update("INSERT INTO chat_summary(session_id,from_turn,to_turn,content,created_at) VALUES(?,41,50,'other-room-summary',CURRENT_TIMESTAMP)",other);
            long q=jdbc.queryForObject("SELECT MAX(message_id) FROM chat_message WHERE session_id=? AND role='user'",Long.class,sessionId);
            var before=jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId);
            var input=questions.loadAnswerInput(sessionId,memberId,profileId,new org.semiproject.pillbadeumserver.chat.dto.QuestionResponse.Question(q,51));
            assertThat(input.recentSummaries()).extracting(AnswerInput.Summary::fromTurn).containsExactly(41,31,21);
            assertThat(input.recentSummaries()).allMatch(s->!s.content().contains("other-room"));
            assertThat(input.recentTurns()).hasSize(6);
            assertThat(input.summaryCount()).isEqualTo(5);
            var ids=new java.util.ArrayList<>(jdbc.queryForList("SELECT summary_id FROM chat_summary",Long.class));ids.add(Long.MAX_VALUE);
            assertThat(summaryStore.selectedEarlier(sessionId,51,ids,input.recentSummaries().stream().map(AnswerInput.Summary::summaryId).toList()))
                    .extracting(AnswerInput.Summary::fromTurn).containsExactly(1,11);
            assertThat(calls.get()).isZero();
            assertThat(jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?",sessionId)).isEqualTo(before);
        }finally {jdbc.update("DELETE FROM chat_summary WHERE session_id=?",other);jdbc.update("DELETE FROM chat_session WHERE session_id=?",other);}
    }

    private JsonNode send(int expected) throws Exception {
        var response=mvc.perform(post("/api/chat-sessions/{s}/messages",sessionId)
                .header("Authorization","Bearer "+tokens.createAccessToken(memberId,"fixture@example.test","ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"합성 복약 질문\"}"))
                .andExpect(status().is(expected)).andReturn().getResponse();
        return JSON.readTree(response.getContentAsString());
    }
    private int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM chat_summary WHERE session_id=?",Integer.class,sessionId);}
    private void seedPairs(int from,int to){for(int i=from;i<=to;i++)jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,?,'user',?),(?,?,'assistant',?)",sessionId,i,"question-"+i,sessionId,i,"answer-"+i+"\n@@SOURCES@@\n[private-source]");}
    private void seedUser(int turn){jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,?,'user','unanswered')",sessionId,turn);}
    private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return key.getKey().longValue();}
    private static void await(BooleanSupplier condition) throws Exception {long end=System.nanoTime()+Duration.ofSeconds(7).toNanos();while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertThat(condition.getAsBoolean()).isTrue();}
}
