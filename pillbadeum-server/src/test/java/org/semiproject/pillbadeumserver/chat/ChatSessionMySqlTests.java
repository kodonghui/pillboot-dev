package org.semiproject.pillbadeumserver.chat;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 DB 커밋·행 잠금을 검증하기 위해 테스트 전체를 @Transactional로 감싸지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class ChatSessionMySqlTests {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private final List<Long> members = new ArrayList<>();
    private long memberId;
    private long profileId;

    @BeforeEach
    void prepareOwnedFixturesInTheDedicatedTestDatabase() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
        }
        memberId = member();
        profileId = profile(memberId, false);
    }

    @AfterEach
    void removeOnlyThisTestsFixtures() {
        for (Long member : members) {
            jdbc.update("DELETE m FROM chat_message m JOIN chat_session s ON s.session_id=m.session_id WHERE s.member_id=?", member);
            jdbc.update("DELETE FROM chat_session WHERE member_id=?", member);
            jdbc.update("DELETE FROM profile WHERE member_id=?", member);
            jdbc.update("DELETE FROM member WHERE member_id=?", member);
        }
        members.clear();
    }

    @Test
    void createsCommittedRoomWithBooleanSnapshotsAndIgnoresBodyMemberId() throws Exception {
        for (boolean pregnant : new boolean[]{false, true}) {
            long profile = pregnant ? profile(memberId, true) : profileId;
            MvcResult result = mvc.perform(post("/api/profiles/{id}/chat-sessions", profile)
                            .header("Authorization", authorization(memberId))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":-99}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$", aMapWithSize(3)))
                    .andExpect(jsonPath("$.title").value(nullValue()))
                    .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                    .andReturn();
            long room = roomId(result);
            var stored = jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?", room);
            assertThat(((Number) stored.get("member_id")).longValue()).isEqualTo(memberId);
            assertThat(((Number) stored.get("profile_id")).longValue()).isEqualTo(profile);
            assertThat(((Number) stored.get("snap_birth_year")).longValue()).isEqualTo(2000);
            assertThat(stored.get("snap_gender")).isEqualTo("FEMALE");
            assertThat(jdbc.queryForObject("SELECT snap_pregnant = ? FROM chat_session WHERE session_id=?",
                    Boolean.class, pregnant, room)).isTrue();
            assertThat(stored.get("last_response_id")).isNull();
            assertThat(stored.get("created_at")).isEqualTo(stored.get("updated_at"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message m JOIN chat_session s "
                + "ON s.session_id=m.session_id WHERE s.member_id=?", Long.class, memberId)).isZero();
    }

    @Test
    void reusesEmptyRoomAndChangesOnlyUpdatedAtEvenAfterProfileChanges() throws Exception {
        long room = roomId(create(memberId, profileId, 201));
        jdbc.update("UPDATE chat_session SET created_at='2020-01-02 03:04:05', updated_at='2020-01-03 03:04:05' WHERE session_id=?", room);
        var before = jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?", room);
        jdbc.update("UPDATE profile SET profile_birth_year=1990, profile_gender='MALE', profile_pregnant=TRUE WHERE profile_id=?", profileId);

        MvcResult reused = create(memberId, profileId, 200);
        assertThat(roomId(reused)).isEqualTo(room);
        assertThat(response(reused).get("createdAt").asString()).isEqualTo("2020-01-02T03:04:05+09:00");
        var after = jdbc.queryForMap("SELECT * FROM chat_session WHERE session_id=?", room);
        assertThat(after.remove("updated_at")).isNotEqualTo(before.remove("updated_at"));
        assertThat(after).isEqualTo(before);
        assertThat(roomCount(memberId, profileId)).isEqualTo(1);
    }

    @Test
    void legacyNullPregnancySnapshotSurvivesReopening() throws Exception {
        long room = roomId(create(memberId, profileId, 201));
        jdbc.update("UPDATE chat_session SET snap_pregnant=NULL WHERE session_id=?", room);
        assertThat(roomId(create(memberId, profileId, 200))).isEqualTo(room);
        assertThat(jdbc.queryForObject("SELECT snap_pregnant FROM chat_session WHERE session_id=?", Boolean.class, room)).isNull();
    }

    @Test
    void anyMessageMakesARoomNonEmptyEvenWithoutATitle() throws Exception {
        long usedRoom = roomId(create(memberId, profileId, 201));
        jdbc.update("INSERT INTO chat_message(session_id,trun_no,role,content) VALUES(?,1,'assistant','fixture')", usedRoom);
        assertThat(roomId(create(memberId, profileId, 201))).isNotEqualTo(usedRoom);
        assertThat(roomCount(memberId, profileId)).isEqualTo(2);
    }

    @Test
    void missingLoginForeignOwnerAndMissingProfileCannotCreateRooms() throws Exception {
        long otherMember = member();
        mvc.perform(post("/api/profiles/{id}/chat-sessions", profileId))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/profiles/{id}/chat-sessions", profileId)
                        .header("Authorization", authorization(otherMember)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/profiles/{id}/chat-sessions", Long.MAX_VALUE)
                        .header("Authorization", authorization(memberId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(roomCount(memberId, profileId)).isZero();
    }

    @Test
    void emptyRoomsAreScopedToBothMemberAndProfile() throws Exception {
        long anotherProfile = profile(memberId, true);
        long otherMember = member();
        long otherProfile = profile(otherMember, false);
        long first = roomId(create(memberId, profileId, 201));
        long second = roomId(create(memberId, anotherProfile, 201));
        long third = roomId(create(otherMember, otherProfile, 201));
        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
        assertThat(roomId(create(memberId, profileId, 200))).isEqualTo(first);
    }

    @Test
    void twoConcurrentCreatesCommitOneRoomAndReturn201And200() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<MvcResult>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return mvc.perform(post("/api/profiles/{id}/chat-sessions", profileId)
                            .header("Authorization", authorization(memberId))).andReturn();
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            MvcResult first = futures.get(0).get(20, TimeUnit.SECONDS);
            MvcResult second = futures.get(1).get(20, TimeUnit.SECONDS);
            assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(201, 200);
            assertThat(roomId(first)).isEqualTo(roomId(second));
        }
        assertThat(roomCount(memberId, profileId)).isEqualTo(1);
    }

    private MvcResult create(long member, long profile, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/profiles/{id}/chat-sessions", profile)
                        .header("Authorization", authorization(member)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private long roomId(MvcResult result) throws Exception {
        return response(result).get("sessionId").asLong();
    }

    private tools.jackson.databind.JsonNode response(MvcResult result) throws Exception {
        return new tools.jackson.databind.json.JsonMapper().readTree(result.getResponse().getContentAsString());
    }

    private long roomCount(long member, long profile) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_session WHERE member_id=? AND profile_id=?", Long.class, member, profile);
    }

    private String authorization(long member) {
        return "Bearer " + tokens.createAccessToken(member, "fixture@example.test", "ROLE_USER");
    }

    private long member() {
        long id = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?, 'fixture-only', 'fixture', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "chat002-" + UUID.randomUUID() + "@example.test");
        members.add(id);
        return id;
    }

    private long profile(long member, boolean pregnant) {
        return insert("INSERT INTO profile(member_id,profile_name,realationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) "
                + "VALUES(?, 'fixture', 'SELF', 2000, 'FEMALE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", member, pregnant);
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
