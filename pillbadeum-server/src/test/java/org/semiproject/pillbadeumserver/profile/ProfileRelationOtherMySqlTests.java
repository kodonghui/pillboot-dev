package org.semiproject.pillbadeumserver.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V3-COMMON-004: 전용 pill_test에서 관계 값 OTHER가 만들기 · 고치기로 실제 저장되고 목록 밖 값은 400인지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class ProfileRelationOtherMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    private long owner;
    private long profile;

    @BeforeEach
    void prepareDedicatedDatabaseAndCommitIsolatedFixtures() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).as("업무 DB에는 테스트 데이터를 쓰지 않는다").isEqualTo("pill_test");
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            owner = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                    + "VALUES(?,'test-only','fixture','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    "relation-owner-" + UUID.randomUUID() + "@example.test");
            profile = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,created_at,updated_at) "
                    + "VALUES(?,'fixture','SELF',2000,'FEMALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", owner);
        });
    }

    @AfterEach
    void removeOnlyThisTestsCommittedFixtures() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM profile WHERE member_id=?", owner);
            jdbc.update("DELETE FROM member WHERE member_id=?", owner);
        });
    }

    @Test
    void otherRelationIsSavedByCreateAndUpdateAndListedBack() throws Exception {
        mvc.perform(post("/api/profiles").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body("친구", "OTHER")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.relationshipType").value("OTHER"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE member_id=? AND profile_name='친구' "
                + "AND relationship_type='OTHER'", Long.class, owner)).isEqualTo(1L);

        mvc.perform(put("/api/profiles/" + profile).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body("이웃", "OTHER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationshipType").value("OTHER"));
        assertThat(relationOf(profile)).isEqualTo("OTHER");

        mvc.perform(get("/api/profiles").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].relationshipType").value(
                        org.hamcrest.Matchers.contains("OTHER", "OTHER")));
    }

    @Test
    void valuesOutsideTheListAreRejectedAndNothingChanges() throws Exception {
        for (String relation : new String[]{"FRIEND", "other", "기타", ""}) {
            mvc.perform(post("/api/profiles").header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body("친구", relation)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            mvc.perform(put("/api/profiles/" + profile).header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body("이웃", relation)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile WHERE member_id=?", Long.class, owner)).isEqualTo(1L);
        assertThat(relationOf(profile)).isEqualTo("SELF");
    }

    private String relationOf(long profileId) {
        return jdbc.queryForObject("SELECT relationship_type FROM profile WHERE profile_id=?", String.class, profileId);
    }
    private String bearer() { return "Bearer " + tokens.createAccessToken(owner, "fixture@example.test", "ROLE_USER"); }
    private static String body(String name, String relation) {
        return "{\"profileName\":\"" + name + "\",\"relationshipType\":\"" + relation + "\",\"profileBirthYear\":1990,"
                + "\"profileGender\":\"FEMALE\",\"profilePregnant\":false}";
    }
    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
