package org.semiproject.pillbadeumserver.kakao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * V3-KAKAO-017(DEC-033): 카톡 복약 알림 대상을 전용 pill_test에서 확인함. 연결마다 지금 고른 프로필의 약만 나오고,
 * 기간·복용 시각은 웹 푸시와 같은 조건이며, 웹 푸시를 켰는지와는 상관없음. 다리 키는 application-test.yaml의 공개 시험 키임.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class KakaoReminderMySqlTests {
    private static final String BRIDGE_KEY = "pillbadeum-test-only-bridge-key";
    private static final String CALLER = "a".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2031, 3, 10);
    private static final String DAY_TEXT = "2031-03-10";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    private final List<Long> members = new ArrayList<>();
    private String itemSeq;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        itemSeq = "K" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,'시험정','fixture','OTC','fixture','ROUND','WHITE','TABLET','fixture',CURRENT_TIMESTAMP)", itemSeq);
    }

    @AfterEach
    void clean() {
        for (long member : members) {
            jdbc.update("DELETE FROM my_drug WHERE profile_id IN (SELECT profile_id FROM profile WHERE member_id=?)", member);
            jdbc.update("DELETE FROM kakao_link WHERE member_id=?", member);
            jdbc.update("DELETE FROM profile WHERE member_id=?", member);
            jdbc.update("DELETE FROM member WHERE member_id=?", member);
        }
        members.clear();
        jdbc.update("DELETE FROM drug WHERE item_seq=?", itemSeq);
    }

    @Test
    void returnsOnlyTheCurrentProfilesDrugsDueAtThatMinute() throws Exception {
        long member = member();
        long self = profile(member, "나");
        long family = profile(member, "엄마");
        String user = hmac('1');
        link(member, user, family);
        long searched = drug(family, itemSeq, null, DAY, DAY, "08:00,20:00");
        long custom = drug(family, null, "직접 쓴 약", DAY.minusDays(1), DAY.plusDays(1), "08:00");
        drug(self, itemSeq, null, DAY, DAY, "08:00"); // 고르지 않은 프로필
        drug(family, null, "다른 시각 약", DAY, DAY, "09:00");
        drug(family, null, "아직 시작 전 약", DAY.plusDays(1), DAY.plusDays(2), "08:00");
        drug(family, null, "이미 끝난 약", DAY.minusDays(2), DAY.minusDays(1), "08:00");

        ask(DAY_TEXT, "08:00").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].kakaoUser").value(user))
                .andExpect(jsonPath("$[0].profileId").value(family))
                .andExpect(jsonPath("$[0].profileName").value("엄마"))
                .andExpect(jsonPath("$[0].drugs", hasSize(2)))
                .andExpect(jsonPath("$[0].drugs[0].myDrugId").value(searched))
                .andExpect(jsonPath("$[0].drugs[0].name").value("시험정"))
                .andExpect(jsonPath("$[0].drugs[1].myDrugId").value(custom))
                .andExpect(jsonPath("$[0].drugs[1].name").value("직접 쓴 약"));
        ask(DAY_TEXT, "20:00").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].drugs", hasSize(1)));
        ask(DAY_TEXT, "10:00").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void eachLinkOfOneMemberIsItsOwnTargetAndWebPushSettingIsIgnored() throws Exception {
        long member = member();
        long self = profile(member, "나");
        long family = profile(member, "엄마");
        String first = hmac('1');
        String second = hmac('2');
        link(member, first, self);
        link(member, second, family);
        drug(self, itemSeq, null, DAY, DAY, "08:00");
        drug(family, itemSeq, null, DAY, DAY, "08:00");
        jdbc.update("INSERT INTO notification_setting(member_id,push_enabled) VALUES(?,FALSE) "
                + "ON DUPLICATE KEY UPDATE push_enabled=FALSE", member);

        ask(DAY_TEXT, "08:00").andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].kakaoUser").value(first))
                .andExpect(jsonPath("$[0].profileName").value("나"))
                .andExpect(jsonPath("$[1].kakaoUser").value(second))
                .andExpect(jsonPath("$[1].profileName").value("엄마"));
        jdbc.update("DELETE FROM notification_setting WHERE member_id=?", member);
    }

    @Test
    void linkWithoutCurrentProfileAndUnlinkedPeopleGetNothing() throws Exception {
        long linked = member();
        long linkedProfile = profile(linked, "나");
        link(linked, hmac('1'), null);
        drug(linkedProfile, itemSeq, null, DAY, DAY, "08:00");
        long notLinked = member();
        drug(profile(notLinked, "나"), itemSeq, null, DAY, DAY, "08:00");

        ask(DAY_TEXT, "08:00").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void badDateOrTimeIsValidationFailed() throws Exception {
        for (String[] bad : new String[][]{{"2031-13-01", "08:00"}, {"2031-02-30", "08:00"}, {"20310310", "08:00"},
                {DAY_TEXT, "8:00"}, {DAY_TEXT, "24:00"}, {DAY_TEXT, "08:60"}, {DAY_TEXT, "08:00,09:00"}, {DAY_TEXT, ""}}) {
            ask(bad[0], bad[1]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/kakao-bridge/reminders").param("date", DAY_TEXT)
                        .header("X-Bridge-Key", BRIDGE_KEY).header("X-Kakao-User", CALLER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void onlyTheBridgeMayAsk() throws Exception {
        long member = member();
        var path = get("/api/kakao-bridge/reminders").param("date", DAY_TEXT).param("time", "08:00");
        mvc.perform(path).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/kakao-bridge/reminders").param("date", DAY_TEXT).param("time", "08:00")
                        .header("X-Bridge-Key", BRIDGE_KEY + "x").header("X-Kakao-User", CALLER))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/kakao-bridge/reminders").param("date", DAY_TEXT).param("time", "08:00")
                        .header("Authorization", "Bearer "
                                + tokens.createAccessToken(member, "reminder@example.test", "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    private ResultActions ask(String date, String time) throws Exception {
        return mvc.perform(get("/api/kakao-bridge/reminders").param("date", date).param("time", time)
                .header("X-Bridge-Key", BRIDGE_KEY).header("X-Kakao-User", CALLER));
    }

    private static String hmac(char c) {
        return String.valueOf(c).repeat(64);
    }

    private long member() {
        long id = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                        + "VALUES(?,'synthetic','reminder','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "reminder-" + UUID.randomUUID() + "@example.test");
        members.add(id);
        return id;
    }

    private long profile(long memberId, String name) {
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,"
                + "created_at,updated_at) VALUES(?,?,'SELF',1970,'FEMALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                memberId, name);
    }

    private void link(long memberId, String user, Long profileId) {
        insert("INSERT INTO kakao_link(member_id,kakao_user_hmac,current_profile_id,notice_version,agreed_at,linked_at) "
                + "VALUES(?,?,?,'KAKAO-LINK-1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", memberId, user, profileId);
    }

    private long drug(long profileId, String item, String customName, LocalDate start, LocalDate end, String times) {
        return insert("INSERT INTO my_drug(profile_id,item_seq,custom_name,dose_per_time,start_date,end_date,dose_times,source) "
                + "VALUES(?,?,?,1,?,?,?,'SEARCH')", profileId, item, customName, start, end, times);
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
