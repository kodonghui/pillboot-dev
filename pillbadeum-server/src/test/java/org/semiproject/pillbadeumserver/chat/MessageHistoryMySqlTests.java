package org.semiproject.pillbadeumserver.chat;

import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semiproject.pillbadeumserver.chat.service.*;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 필터·읽기 Service·JPA·MySQL로 이력을 검증한다. 생성 흐름 검증은 기존 답변 테스트의 범위이다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class MessageHistoryMySqlTests {
    private static final JsonMapper JSON = new JsonMapper();
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired QuestionStore store;
    @Autowired AnswerSources sources;
    @MockitoBean QuestionClassifier classifier;
    @MockitoSpyBean OpenAiClient openAi;
    @MockitoSpyBean PublicDataClient publicData;
    private final List<Long> members = new ArrayList<>();
    private final List<String> drugCodes = new ArrayList<>();
    private long memberId;
    private long profileId;
    private long roomId;

    @BeforeEach
    void prepareOnlySyntheticOwnedFixtures() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var connection = dataSource.getConnection()) {
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }
        memberId = member();profileId = profile(memberId);roomId = room(memberId, profileId);
    }

    @AfterEach
    void cleanupOnlyThisTestsData() {
        for (long member : members) {
            jdbc.update("DELETE m FROM chat_message m JOIN chat_session s ON s.session_id=m.session_id WHERE s.member_id=?", member);
            jdbc.update("DELETE FROM chat_session WHERE member_id=?", member);
        }
        for (long member : members) {
            jdbc.update("DELETE d FROM my_drug d JOIN profile p ON p.profile_id=d.profile_id WHERE p.member_id=?", member);
            jdbc.update("DELETE FROM profile WHERE member_id=?", member);
            jdbc.update("DELETE FROM member WHERE member_id=?", member);
        }
        for (String code : drugCodes) jdbc.update("DELETE FROM drug WHERE item_seq=?", code);
        verifyNoInteractions(openAi, publicData);
        members.clear();drugCodes.clear();
    }

    @Test
    void answerSnapshotKeepsUnidentifiedRegisteredNamesOutsideProviderLookups() {
        jdbc.update("INSERT INTO my_drug(profile_id,item_seq,custom_name,dose_per_time,source) VALUES(?,NULL,?,1,'MANUAL')",
                profileId, "합성 직접입력약");
        var question = store.save(roomId, memberId, profileId, "합성 질문");
        var input = store.loadAnswerInput(roomId, memberId, profileId, question);
        assertThat(input.medications()).isEmpty();
        assertThat(input.unidentifiedDrugNames()).containsExactly("합성 직접입력약");
        assertThat(input.withSimilarSummaries(List.of()).unidentifiedDrugNames()).containsExactly("합성 직접입력약");
    }

    @Test
    void emptyRoomReturnsItsHeaderAndDoesNotChangeAnyStoredState(CapturedOutput output) throws Exception {
        var before = roomState();var profile = jdbc.queryForMap("SELECT * FROM profile WHERE profile_id=?", profileId);
        for (int i = 0; i < 2; i++) {
            var result = read(roomId, null, null);
            assertThat(result.path("sessionId").asLong()).isEqualTo(roomId);
            assertThat(result.path("profileName").asString()).isEqualTo("history");
            assertThat(result.path("title").isNull()).isTrue();
            assertThat(result.path("drugCount").asLong()).isZero();
            assertThat(result.path("retryMessageId").isNull()).isTrue();
            assertThat(result.path("messages").size()).isZero();
            assertThat(result.path("hasMore").asBoolean()).isFalse();
            assertThat(result.has("snapBirthYear") || result.has("memberId") || result.has("lastResponseId")).isFalse();
        }
        assertThat(roomState()).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM profile WHERE profile_id=?", profileId)).isEqualTo(profile);
        assertThat(output.getAll()).doesNotContain("history-member-private", "history");
    }

    @ParameterizedTest
    @ValueSource(ints = {29, 30, 31})
    void defaultPageCountsDistinctTurnsInsteadOfMessageRows(int count) throws Exception {
        for (int turn = 1; turn <= count; turn++) pair(roomId, turn);
        var result = read(roomId, null, null);
        assertThat(result.path("messages").size()).isEqualTo(Math.min(30, count) * 2);
        assertThat(result.path("messages").path(0).path("turnNo").asInt()).isEqualTo(Math.max(1, count - 29));
        assertThat(result.path("messages").path(result.path("messages").size()-1).path("turnNo").asInt()).isEqualTo(count);
        assertThat(result.path("hasMore").asBoolean()).isEqualTo(count > 30);
        assertThat(result.path("retryMessageId").isNull()).isTrue();
    }

    @Test
    void cursorDoesNotSplitPairsAndRetryIdUsesTheRoomRatherThanThePage() throws Exception {
        for (int turn = 1; turn <= 32; turn++) pair(roomId, turn);
        long last = message(roomId, 33, "user", "unanswered latest", "2026-10-19 09:02:00");
        var first = read(roomId, null, null);
        assertThat(first.path("messages").size()).isEqualTo(59);
        assertThat(turns(first)).startsWith(4,4).endsWith(32,32,33);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        var older = read(roomId, 4, 30);
        assertThat(turns(older)).containsExactly(1,1,2,2,3,3);
        assertThat(older.path("retryMessageId").asLong()).isEqualTo(last);
        assertThat(older.path("hasMore").asBoolean()).isFalse();
        var latestOne = read(roomId, null, 1);
        assertThat(turns(latestOne)).containsExactly(33);
        assertThat(latestOne.path("hasMore").asBoolean()).isTrue();
        var exhausted = read(roomId, 1, 30);
        assertThat(exhausted.path("messages").size()).isZero();
        assertThat(exhausted.path("hasMore").asBoolean()).isFalse();
        assertThat(exhausted.path("retryMessageId").asLong()).isEqualTo(last);
    }

    @Test
    void cursorHandlesGapsAndIntegerMaxTurnWithoutAnOffsetAssumption() throws Exception {
        pair(roomId, 1);pair(roomId, 10);pair(roomId, 100);pair(roomId, Integer.MAX_VALUE);
        assertThat(turns(read(roomId, null, 2))).containsExactly(100,100,Integer.MAX_VALUE,Integer.MAX_VALUE);
        var older = read(roomId, 100, 2);
        assertThat(turns(older)).containsExactly(1,1,10,10);
        assertThat(older.path("hasMore").asBoolean()).isFalse();
        assertThat(turns(read(roomId, Integer.MAX_VALUE, 30))).containsExactly(1,1,10,10,100,100);
    }

    @Test
    void tiedTimestampsAndLegacyDuplicatesKeepAllOriginalRowsInStableIdOrder() throws Exception {
        long a = message(roomId, 7, "user", "first", "2026-10-19 09:00:00");
        long b = message(roomId, 7, "user", "second", "2026-10-19 09:00:00");
        long c = message(roomId, 7, "assistant", "third", "2026-10-19 09:00:00");
        pair(roomId, 2);
        var result = read(roomId, null, 1);
        assertThat(ids(result)).containsExactly(a,b,c);
        assertThat(result.path("retryMessageId").isNull()).isTrue();
        assertThat(result.path("hasMore").asBoolean()).isTrue();
        assertThat(ids(read(roomId, null, 1))).containsExactly(a,b,c);
    }

    @Test
    void onlyTheLastUserQuestionCanBeARetryTargetAndOtherRoomsDoNotAnswerIt() throws Exception {
        message(roomId, 1, "user", "old unanswered", "2026-10-19 09:00:00");
        pair(roomId, 2);
        assertThat(read(roomId, null, 30).path("retryMessageId").isNull()).isTrue();
        long latest = message(roomId, 3, "user", "new unanswered", "2026-10-19 08:00:00");
        long other = room(memberId, profileId);pair(other, 3);
        assertThat(read(roomId, null, 30).path("retryMessageId").asLong()).isEqualTo(latest);
        long newerSameTurn = message(roomId, 3, "user", "duplicate latest", "2026-10-19 08:00:00");
        assertThat(read(roomId, 2, 30).path("retryMessageId").asLong()).isEqualTo(newerSameTurn);
        message(roomId, 3, "assistant", "answered", "2026-10-19 08:01:00");
        assertThat(read(roomId, 2, 30).path("retryMessageId").isNull()).isTrue();
    }

    @Test
    void storedAnswerRoundTripsSourceFooterKstRolesAndIdentifiersWithoutMutation() throws Exception {
        var question = store.save(roomId, memberId, profileId, "history-private-question");
        var source = new AnswerSource("synthetic-code", "synthetic drug", AnswerSource.Type.DUR, LocalDate.of(2026,10,17), true, false);
        String content = sources.append("history-private-answer", List.of(source));
        var answer = store.saveAnswer(roomId, memberId, profileId, question, new GeneratedAnswer(content, "synthetic-response", null),
                ChatDeadline.after(Duration.ofSeconds(5)));
        var before = roomState();var raw = jdbc.queryForList("SELECT * FROM chat_message WHERE session_id=? ORDER BY message_id", roomId);
        var result = read(roomId, null, null);
        assertThat(ids(result)).containsExactly(question.messageId(), answer.messageId());
        assertThat(result.path("messages").path(0).path("role").asString()).isEqualTo("USER");
        assertThat(result.path("messages").path(1).path("role").asString()).isEqualTo("ASSISTANT");
        assertThat(result.path("messages").path(1).path("content").asString()).isEqualTo(content);
        assertThat(sources.sources(result.path("messages").path(1).path("content").asString())).containsExactly(source);
        // 같은 시각도 분 정각에는 toString이 :00을 생략한다. 표현 대신 값과 KST 오프셋을 확인한다.
        var restoredTime = OffsetDateTime.parse(result.path("messages").path(1).path("createdAt").asString());
        assertThat(restoredTime).isEqualTo(answer.createdAt());
        assertThat(restoredTime.getOffset()).isEqualTo(answer.createdAt().getOffset());
        assertThat(result.path("messages").path(0).path("createdAt").asString()).endsWith("+09:00");
        assertThat(result.path("messages").path(0).has("tokenCount")).isFalse();
        assertThat(roomState()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM chat_message WHERE session_id=? ORDER BY message_id", roomId)).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-20 12:43:00", "2026-10-20 12:43:01"})
    void historyPreservesTimestampValueAndKstAtZeroAndNonzeroSeconds(String storedTime) throws Exception {
        message(roomId, 1, "user", "synthetic timestamp question", storedTime);
        long answer = message(roomId, 1, "assistant", "synthetic timestamp answer", storedTime);
        var result = read(roomId, null, 30).path("messages").path(1);
        var expected = LocalDateTime.parse(storedTime.replace(' ', 'T')).atZone(
                org.semiproject.pillbadeumserver.common.config.TimeZoneConfig.ZONE).toOffsetDateTime();
        var restored = OffsetDateTime.parse(result.path("createdAt").asString());
        assertThat(result.path("messageId").asLong()).isEqualTo(answer);
        assertThat(restored).isEqualTo(expected);
        assertThat(restored.getOffset()).isEqualTo(expected.getOffset());
    }

    @Test
    void currentNameAndMedicationCountStayWithinTheRoomsProfileAndKeepNullSnapshot() throws Exception {
        String code = drug();addMedication(profileId, code);addMedication(profileId, drug());
        long otherProfile = profile(memberId);addMedication(otherProfile, code);
        long otherRoom = room(memberId, otherProfile);pair(otherRoom, 99);pair(roomId, 1);
        jdbc.update("UPDATE profile SET profile_name='renamed' WHERE profile_id=?", profileId);
        var before = roomState();
        var result = read(roomId, null, null);
        assertThat(result.path("profileName").asString()).isEqualTo("renamed");
        assertThat(result.path("drugCount").asLong()).isEqualTo(2);
        assertThat(turns(result)).containsExactly(1,1);
        assertThat(roomState()).isEqualTo(before);
        assertThat(before.get("snap_pregnant")).isNull();
    }

    @Test
    void missingForeignAndMismatchedOwnersDoNotExposeHistory() throws Exception {
        pair(roomId, 1);long otherMember = member();long otherProfile = profile(otherMember);
        mvc.perform(get("/api/chat-sessions/{id}/messages", roomId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chat-sessions/{id}/messages", roomId).header("Authorization", auth(otherMember)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$", aMapWithSize(2)));
        long wrongProfile = room(memberId, otherProfile);
        mvc.perform(get("/api/chat-sessions/{id}/messages", wrongProfile).header("Authorization", auth(memberId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        long wrongMember = room(otherMember, profileId);
        mvc.perform(get("/api/chat-sessions/{id}/messages", wrongMember).header("Authorization", auth(memberId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/chat-sessions/{id}/messages", Long.MAX_VALUE).header("Authorization", auth(memberId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void internalRolesDoNotUseUpTurnsAndAnAssistantOnlyRoomHasNoRetry() throws Exception {
        message(roomId, 999, "system", "internal-only", "2026-10-19 09:00:00");
        message(roomId, 1, "assistant", "legacy assistant", "2026-10-19 09:00:00");
        var result = read(roomId, null, 1);
        assertThat(turns(result)).containsExactly(1);
        assertThat(result.path("hasMore").asBoolean()).isFalse();
        assertThat(result.path("retryMessageId").isNull()).isTrue();
        assertThat(result.toString()).doesNotContain("internal-only");
    }

    @Test
    void blockedPostDoesNotReappearInHistoryOrTriggerProviders() throws Exception {
        when(classifier.classify(anyString())).thenReturn(QuestionCategory.OFF_TOPIC);
        mvc.perform(post("/api/chat-sessions/{id}/messages", roomId).header("Authorization", auth(memberId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"blocked fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("BLOCKED"));
        assertThat(read(roomId, null, null).path("messages").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?", Integer.class, roomId)).isZero();
    }

    private JsonNode read(long room, Integer before, Integer limit) throws Exception {
        var request = get("/api/chat-sessions/{id}/messages", room).header("Authorization", auth(memberId))
                .param("memberId", "-999").param("profileId", "-999");
        if (before != null) request.param("beforeTurn", before.toString());
        if (limit != null) request.param("limit", limit.toString());
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(8)))
                .andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }
    private List<Integer> turns(JsonNode result) { return IntStream.range(0,result.path("messages").size()).mapToObj(i -> result.path("messages").path(i).path("turnNo").asInt()).toList(); }
    private List<Long> ids(JsonNode result) { return IntStream.range(0,result.path("messages").size()).mapToObj(i -> result.path("messages").path(i).path("messageId").asLong()).toList(); }
    private Map<String,Object> roomState() { return jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?", roomId); }
    private String auth(long member) { return "Bearer " + tokens.createAccessToken(member, "fixture@example.test", "ROLE_USER"); }
    private void pair(long room, int turn) {
        message(room, turn, "user", "question-"+turn, "2026-10-19 09:00:00");
        message(room, turn, "assistant", "answer-"+turn, "2026-10-19 09:01:00");
    }
    private long message(long room, int turn, String role, String text, String date) {
        return insert("INSERT INTO chat_message(session_id,trun_no,role,content,created_at) VALUES(?,?,?,?,?)", room,turn,role,text,date);
    }
    private long member() {
        long id=insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) VALUES(?,'fixture','history-member-private','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "chat003-"+UUID.randomUUID()+"@example.test");members.add(id);return id;
    }
    private long profile(long member) { return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) VALUES(?,'history','SELF',2000,'FEMALE',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member); }
    private long room(long member, long profile) { return insert("INSERT INTO chat_session(member_id,profile_id,title,snap_birth_year,snap_gender,snap_pregnant,created_at,updated_at) VALUES(?,?,NULL,1990,'FEMALE',NULL,'2026-10-17 09:00:00','2026-10-18 09:00:00')",member,profile); }
    private String drug() {
        String code="history-"+UUID.randomUUID();
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) VALUES(?,'fixture','fixture','OTC','fixture','ROUND','WHITE','TABLET','fixture',CURRENT_TIMESTAMP)",code);drugCodes.add(code);return code;
    }
    private void addMedication(long profile,String code) { jdbc.update("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,start_date,end_date,dose_times,source) VALUES(?,?,1,CURRENT_DATE,CURRENT_DATE,'07:00','SEARCH')",profile,code); }
    private long insert(String sql,Object... values) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection -> { var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement;},key);
        return key.getKey().longValue();
    }
}
