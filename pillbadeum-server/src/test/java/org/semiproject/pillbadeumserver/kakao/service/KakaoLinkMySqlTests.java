package org.semiproject.pillbadeumserver.kakao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * V3-KAKAO-012~016·V3-FL-31·32(DEC-029): 웹에서 받은 코드로 카톡 사용자를 회원에 잇고, 그 회원의 프로필로 정한 API를 부르고,
 * 웹·카톡 어느 쪽에서든 끊는 흐름을 전용 pill_test에서 확인함. 다리 키는 application-test.yaml의 공개 시험 키임.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class KakaoLinkMySqlTests {
    private static final String BRIDGE_KEY = "pillbadeum-test-only-bridge-key";
    private static final JsonMapper JSON = new JsonMapper();

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired KakaoLinkAttempts attempts;
    private long member, self, family, stranger, strangerProfile;
    private String kakao, otherKakao;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        attempts.clear();
        member = member();
        self = profile(member, "나", "SELF");
        family = profile(member, "엄마", "FAMILY");
        stranger = member();
        strangerProfile = profile(stranger, "남", "SELF");
        kakao = KakaoLinkCodes.sha256Hex("kakao-" + UUID.randomUUID());
        otherKakao = KakaoLinkCodes.sha256Hex("kakao-" + UUID.randomUUID());
    }

    @AfterEach
    void clean() {
        attempts.clear();
        for (long id : new long[]{member, stranger}) {
            jdbc.update("DELETE FROM chat_message WHERE session_id IN (SELECT session_id FROM chat_session WHERE member_id=?)", id);
            jdbc.update("DELETE FROM chat_session WHERE member_id=?", id);
            // 연결·코드는 회원을 지우면 함께 지워짐(ON DELETE CASCADE).
            jdbc.update("DELETE FROM profile WHERE member_id=?", id);
            jdbc.update("DELETE FROM member WHERE member_id=?", id);
        }
    }

    @Test
    void aWebCodeLinksKakaoToEveryProfileOfTheMemberUntilEitherSideUnlinks() throws Exception {
        // 안내를 확인하지 않으면 코드를 주지 않음.
        for (String body : new String[]{"{}", "{\"agreed\":false}"}) {
            mvc.perform(post("/api/kakao-link-codes").header("Authorization", bearer(member))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KAKAO_LINK_NOT_AGREED"));
        }
        String first = newCode(member);
        String second = newCode(member);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kakao_link_code WHERE member_id=? AND revoked_at IS NOT NULL",
                Integer.class, member)).as("A new code revokes the earlier open code").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT code_hash FROM kakao_link_code WHERE member_id=?", String.class, member))
                .as("Only hashes are stored").allMatch(hash -> hash.matches("[0-9a-f]{64}"))
                .doesNotContain(first.replace("-", ""), second.replace("-", ""));

        bridgeLink(kakao, first).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_CODE_NOT_FOUND"));
        // 소문자·공백으로 보내도 같은 코드임. 프로필이 둘이라 지금 보는 프로필은 아직 없음.
        var linked = bridgeLink(kakao, " " + second.toLowerCase().replace("-", " ") + " ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentProfileId").value(nullValue()))
                .andExpect(jsonPath("$.profiles.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long linkId = JSON.readTree(linked).get("linkId").asLong();
        bridgeLink(otherKakao, second).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_CODE_NOT_FOUND"));
        bridgeLink(kakao, newCode(member)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KAKAO_ALREADY_LINKED"));

        // 연결된 회원으로 정한 API만 부름. 다른 회원의 프로필은 기존 소유권 검사가 403으로 막음.
        mvc.perform(bridge(get("/api/profiles/{id}/drugs", family), kakao)).andExpect(status().isOk());
        mvc.perform(bridge(get("/api/profiles/{id}/drugs", strangerProfile), kakao)).andExpect(status().isForbidden());
        mvc.perform(bridge(get("/api/profiles"), kakao)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("KAKAO_BRIDGE_FORBIDDEN"));
        mvc.perform(bridge(get("/api/kakao-bridge/links/current"), otherKakao)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_NOT_FOUND"));
        mvc.perform(bridge(get("/api/profiles/{id}/drugs", family), otherKakao)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_NOT_FOUND"));
        // 회원 JWT로는 다리 전용 API를 부를 수 없음.
        mvc.perform(get("/api/kakao-bridge/links/current").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());

        // 프로필 고르기: 남의 프로필은 404, 내 프로필은 지금 보는 프로필이 됨.
        selectProfile(kakao, strangerProfile).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        selectProfile(kakao, family).andExpect(status().isOk()).andExpect(jsonPath("$.currentProfileId").value(family));

        // 카톡에서 연 상담방은 카톡 꼬리표가 붙음(9.2 Q5 B안).
        var room = mvc.perform(bridge(post("/api/profiles/{id}/chat-sessions", family), kakao))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.channel").value("KAKAO"))
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT channel FROM chat_session WHERE session_id=?", String.class,
                JSON.readTree(room).get("sessionId").asLong())).isEqualTo("KAKAO");

        mvc.perform(get("/api/kakao-links").header("Authorization", bearer(member))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].linkId").value(linkId))
                .andExpect(jsonPath("$[0].currentProfileName").value("엄마"));
        mvc.perform(get("/api/kakao-links").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // 웹에서 끊기: 남의 연결은 404, 내 연결은 204. 그 뒤 카톡 요청은 연결 없음.
        mvc.perform(delete("/api/kakao-links/{id}", linkId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("KAKAO_LINK_NOT_FOUND"));
        mvc.perform(delete("/api/kakao-links/{id}", linkId).header("Authorization", bearer(member)))
                .andExpect(status().isNoContent());
        mvc.perform(bridge(get("/api/profiles/{id}/drugs", family), kakao)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_NOT_FOUND"));

        // 다시 이은 뒤 카톡에서 끊기.
        bridgeLink(kakao, newCode(member)).andExpect(status().isCreated());
        mvc.perform(bridge(delete("/api/kakao-bridge/links/current"), kakao)).andExpect(status().isNoContent());
        mvc.perform(bridge(delete("/api/kakao-bridge/links/current"), kakao)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_NOT_FOUND"));
    }

    @Test
    void aMemberWithOneProfileStartsOnThatProfileAndDeletingItOnlyClearsIt() throws Exception {
        jdbc.update("DELETE FROM profile WHERE profile_id=?", family);
        bridgeLink(kakao, newCode(member)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentProfileId").value(self));
        jdbc.update("DELETE FROM profile WHERE profile_id=?", self);
        mvc.perform(bridge(get("/api/kakao-bridge/links/current"), kakao)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentProfileId").value(nullValue()))
                .andExpect(jsonPath("$.profiles.length()").value(0));
    }

    @Test
    void codesAndLinksHaveLimits() throws Exception {
        for (int i = 0; i < KakaoLinkService.MAX_CODES_PER_WINDOW - 1; i++) {
            newCode(member);
        }
        String last = newCode(member);
        requestCode(member).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_CODE_RATE_LIMITED"));

        // 연결이 5개면 코드를 주지 않고, 이미 받은 코드로도 잇지 않음.
        LocalDateTime now = LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < KakaoLinkService.MAX_LINKS; i++) {
            jdbc.update("INSERT INTO kakao_link(member_id,kakao_user_hmac,notice_version,agreed_at,linked_at) "
                    + "VALUES(?,?,'KAKAO-LINK-1',?,?)", member, KakaoLinkCodes.sha256Hex("fill-" + i + kakao), now, now);
        }
        bridgeLink(kakao, last).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KAKAO_LINK_LIMIT"));
        jdbc.update("DELETE FROM kakao_link_code WHERE member_id=?", member);
        requestCode(member).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KAKAO_LINK_LIMIT"));
    }

    @Test
    void expiredCodesWrongShapesAndFiveWrongCodesAreRefused() throws Exception {
        LocalDateTime now = LocalDateTime.now(TimeZoneConfig.ZONE).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("INSERT INTO kakao_link_code(member_id,code_hash,notice_version,agreed_at,created_at,expires_at) "
                + "VALUES(?,?,'KAKAO-LINK-1',?,?,?)", member, KakaoLinkCodes.hash("7K3QM9XA"),
                now.minusMinutes(11), now.minusMinutes(11), now.minusMinutes(1));
        bridgeLink(kakao, "7K3Q-M9XA").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_CODE_NOT_FOUND"));
        bridgeLink(kakao, "안녕하세요").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (int i = 0; i < 3; i++) {
            bridgeLink(kakao, "2222-2222").andExpect(status().isNotFound());
        }
        // 다섯 번 틀린 뒤에는 맞는 코드도 1시간 받지 않음. 다른 카톡 사용자는 영향 없음.
        String good = newCode(member);
        bridgeLink(kakao, good).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("KAKAO_LINK_LOCKED"));
        bridgeLink(otherKakao, good).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kakao_link WHERE member_id=?", Integer.class, member))
                .isEqualTo(1);
    }

    private String newCode(long memberId) throws Exception {
        var body = requestCode(memberId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("code").asString();
    }

    private ResultActions requestCode(long memberId) throws Exception {
        return mvc.perform(post("/api/kakao-link-codes").header("Authorization", bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":true}"));
    }

    private ResultActions bridgeLink(String user, String code) throws Exception {
        return mvc.perform(bridge(post("/api/kakao-bridge/links"), user).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("code", code))));
    }

    private ResultActions selectProfile(String user, long profileId) throws Exception {
        return mvc.perform(bridge(put("/api/kakao-bridge/links/current/profile"), user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"profileId\":" + profileId + "}"));
    }

    private static MockHttpServletRequestBuilder bridge(MockHttpServletRequestBuilder request, String user) {
        return request.header("X-Bridge-Key", BRIDGE_KEY).header("X-Kakao-User", user);
    }

    private String bearer(long memberId) {
        return "Bearer " + tokens.createAccessToken(memberId, "link-" + memberId + "@example.test", "ROLE_USER");
    }

    private long member() {
        return insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'synthetic','link','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "link-" + UUID.randomUUID() + "@example.test");
    }

    private long profile(long memberId, String name, String relation) {
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,"
                + "created_at,updated_at) VALUES(?,?,?,1970,'FEMALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                memberId, name, relation);
    }

    private long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
