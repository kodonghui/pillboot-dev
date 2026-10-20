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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CHAT-001·002의 저장·정렬·소유권·동시 생성을 실제 pill_test DB에서 확인한다.
 * 실제 커밋·행 잠금을 검증하기 위해 테스트 전체를 @Transactional로 감싸지 않는다.
 * 각 실행이 만든 데이터는 AfterEach에서 정리하며 MYSQL_TEST_URL이 없으면 이 클래스는 생략된다.
 */
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
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }
        memberId = member();
        profileId = profile(memberId, false);
    }

    @AfterEach
    void removeOnlyThisTestsFixtures() {
        // 회원·프로필이 잘못 조합된 행도 검사하므로 모든 방부터 지워 프로필 FK 참조를 먼저 해제한다.
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

    @Test
    void listScopesBothMemberAndProfileAndKeepsStableRecentOrderWithoutWrites() throws Exception {
        long older = session(memberId, profileId, "earlier", "2026-10-18 09:00:00");
        long recent = session(memberId, profileId, "recent", "2026-10-19 09:00:00");
        long tied = session(memberId, profileId, null, "2026-10-19 09:00:00");
        jdbc.update("UPDATE chat_session SET snap_pregnant=NULL WHERE session_id=?", tied);
        long anotherProfile = profile(memberId, true);
        long anotherRoom = session(memberId, anotherProfile, "another-profile", "2026-10-20 09:00:00");
        long otherMember = member();
        session(otherMember, profile(otherMember, false), "another-member", "2026-10-21 09:00:00");
        // 기존 스키마는 두 FK만 확인하므로 잘못 조합된 행도 목록의 member_id 조건으로 제외해야 한다.
        session(otherMember, profileId, "mismatched-owner", "2026-10-22 09:00:00");
        var before = jdbc.queryForList("SELECT * FROM chat_session WHERE profile_id=? ORDER BY session_id", profileId);
        var profileBefore = jdbc.queryForMap("SELECT * FROM profile WHERE profile_id=?", profileId);

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(get("/api/profiles/{id}/chat-sessions", profileId)
                            .param("memberId", Long.toString(otherMember))
                            .header("Authorization", authorization(memberId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", aMapWithSize(1)))
                    .andExpect(jsonPath("$.sessions.length()").value(3))
                    .andExpect(jsonPath("$.sessions[0]", aMapWithSize(3)))
                    .andExpect(jsonPath("$.sessions[0].sessionId").value(tied))
                    .andExpect(jsonPath("$.sessions[0].title").value(nullValue()))
                    .andExpect(jsonPath("$.sessions[0].updatedAt").value("2026-10-19T09:00:00+09:00"))
                    .andExpect(jsonPath("$.sessions[1].sessionId").value(recent))
                    .andExpect(jsonPath("$.sessions[2].sessionId").value(older));
        }
        mvc.perform(get("/api/profiles/{id}/chat-sessions", anotherProfile)
                        .header("Authorization", authorization(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].sessionId").value(anotherRoom));

        assertThat(jdbc.queryForList("SELECT * FROM chat_session WHERE profile_id=? ORDER BY session_id", profileId))
                .isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM profile WHERE profile_id=?", profileId)).isEqualTo(profileBefore);
    }

    @Test
    void emptyListDoesNotCreateARoomOrChangeTheProfile() throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM profile WHERE profile_id=?", profileId);
        mvc.perform(get("/api/profiles/{id}/chat-sessions", profileId)
                        .header("Authorization", authorization(memberId)))
                .andExpect(status().isOk()).andExpect(content().json("{\"sessions\":[]}"));
        assertThat(roomCount(memberId, profileId)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM profile WHERE profile_id=?", profileId)).isEqualTo(before);
    }

    @Test
    void listRejectsMissingLoginForeignOwnerAndMissingProfile() throws Exception {
        session(memberId, profileId, "owned-room", "2026-10-19 09:00:00");
        mvc.perform(get("/api/profiles/{id}/chat-sessions", profileId))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/profiles/{id}/chat-sessions", profileId)
                        .header("Authorization", authorization(member())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$", aMapWithSize(2)));
        mvc.perform(get("/api/profiles/{id}/chat-sessions", Long.MAX_VALUE)
                        .header("Authorization", authorization(memberId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(roomCount(memberId, profileId)).isEqualTo(1);
    }

    @Test
    void listReturnsAllRoomsWithoutPagination() throws Exception {
        for (int i = 0; i < 35; i++) {
            session(memberId, profileId, "fixture-" + i, "2026-10-19 09:00:00");
        }
        mvc.perform(get("/api/profiles/{id}/chat-sessions", profileId)
                        .header("Authorization", authorization(memberId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions.length()").value(35));
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
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) "
                + "VALUES(?, 'fixture', 'SELF', 2000, 'FEMALE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", member, pregnant);
    }

    private long session(long member, long profile, String title, String updatedAt) {
        return insert("INSERT INTO chat_session(member_id,profile_id,title,created_at,updated_at,snap_birth_year,snap_gender,snap_pregnant) "
                + "VALUES(?, ?, ?, '2026-10-17 09:00:00', ?, 2000, 'FEMALE', FALSE)", member, profile, title, updatedAt);
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
