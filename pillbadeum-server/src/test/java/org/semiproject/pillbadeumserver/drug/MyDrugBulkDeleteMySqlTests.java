package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.medication.domain.MedicationReminderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V3-DRUG-003 · V3-COMMON-003: 전용 pill_test에서 HTTP → 서비스 → 실제 커밋 → 독립 JDBC 조회로 여러 개 삭제를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MyDrugBulkDeleteMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired MedicationReminderRepository reminders;
    private Fixture fixture;
    private LocalDate today;
    private record Fixture(long owner, long foreign, long profile, long foreignProfile,
                           long first, long second, long third, long foreignDrug) {}

    @BeforeEach
    void prepareDedicatedDatabaseAndCommitIsolatedFixtures() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).as("업무 DB에는 테스트 데이터를 쓰지 않는다").isEqualTo("pill_test");
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        fixture = new TransactionTemplate(transactions).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            long owner = member("bulk-owner-" + suffix + "@example.test");
            long foreign = member("bulk-foreign-" + suffix + "@example.test");
            long profile = profile(owner), foreignProfile = profile(foreign);
            jdbc.update("INSERT INTO notification_setting(member_id,push_enabled,updated_at) VALUES(?,TRUE,CURRENT_TIMESTAMP)", owner);
            return new Fixture(owner, foreign, profile, foreignProfile, medication(profile, "합성첫째약"),
                    medication(profile, "합성둘째약"), medication(profile, "합성셋째약"), medication(foreignProfile, "합성남의약"));
        });
    }

    @AfterEach
    void removeOnlyThisTestsCommittedFixtures() {
        if (fixture == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM my_drug WHERE profile_id IN (?,?)", fixture.profile, fixture.foreignProfile);
            jdbc.update("DELETE FROM notification_setting WHERE member_id IN (?,?)", fixture.owner, fixture.foreign);
            jdbc.update("DELETE FROM profile WHERE profile_id IN (?,?)", fixture.profile, fixture.foreignProfile);
            jdbc.update("DELETE FROM member WHERE member_id IN (?,?)", fixture.owner, fixture.foreign);
        });
    }

    @Test
    void chosenDrugsAreDeletedTogetherWithTheirSchedulesAndRemindersAndTheRestStays() throws Exception {
        assertThat(reminders.targets(today, "07:00"))
                .anyMatch(target -> target.memberId() == fixture.owner && target.drugCount() == 3);

        bulkDelete(fixture.profile, fixture.first, fixture.second)
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletedCount").value(2));

        assertThat(remaining()).containsExactly(fixture.third);
        assertThat(exists(fixture.foreignDrug)).isTrue();
        assertThat(reminders.targets(today, "07:00"))
                .anyMatch(target -> target.memberId() == fixture.owner && target.drugCount() == 1);
        mvc.perform(get("/api/calendar/doses").header("Authorization", bearer()).param("date", today.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doses.length()").value(1))
                .andExpect(jsonPath("$.doses[0].drugName").value("합성셋째약"));
    }

    @Test
    void anyMissingOrOtherProfileDrugDeletesNothingAndARetryIsNotFound() throws Exception {
        bulkDelete(fixture.profile, fixture.first, fixture.foreignDrug)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        bulkDelete(fixture.profile, fixture.first, Long.MAX_VALUE)
                .andExpect(status().isNotFound());
        assertThat(remaining()).containsExactly(fixture.first, fixture.second, fixture.third);
        assertThat(exists(fixture.foreignDrug)).isTrue();

        bulkDelete(fixture.profile, fixture.first, fixture.second).andExpect(status().isOk());
        // 같은 요청을 다시 보내면 첫 요청이 이미 지웠으므로 404이고 남은 약은 그대로임
        bulkDelete(fixture.profile, fixture.first, fixture.second).andExpect(status().isNotFound());
        bulkDelete(fixture.profile, fixture.second, fixture.third).andExpect(status().isNotFound());
        assertThat(remaining()).containsExactly(fixture.third);
    }

    @Test
    void foreignOrMissingProfileIsNotFoundAndInvalidOrAnonymousRequestsDeleteNothing() throws Exception {
        bulkDelete(fixture.foreignProfile, fixture.foreignDrug)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        bulkDelete(Long.MAX_VALUE, fixture.first).andExpect(status().isNotFound());
        bulkDelete(fixture.profile, fixture.first, fixture.first)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(path(fixture.profile)).contentType(MediaType.APPLICATION_JSON).content(body(fixture.first)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(remaining()).containsExactly(fixture.first, fixture.second, fixture.third);
        assertThat(exists(fixture.foreignDrug)).isTrue();
    }

    private ResultActions bulkDelete(long profileId, long... myDrugIds) throws Exception {
        return mvc.perform(post(path(profileId)).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body(myDrugIds)));
    }
    private static String path(long profileId) { return "/api/profiles/" + profileId + "/drugs/bulk-delete"; }
    private static String body(long... myDrugIds) {
        return "{\"myDrugIds\":[" + Arrays.stream(myDrugIds).mapToObj(Long::toString).collect(Collectors.joining(",")) + "]}";
    }
    private String bearer() { return "Bearer " + tokens.createAccessToken(fixture.owner, "fixture@example.test", "ROLE_USER"); }
    private java.util.List<Long> remaining() {
        return jdbc.queryForList("SELECT my_drug_id FROM my_drug WHERE profile_id=? ORDER BY my_drug_id", Long.class, fixture.profile);
    }
    private boolean exists(long myDrugId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE my_drug_id=?", Long.class, myDrugId) == 1L;
    }
    private long member(String email) {
        return insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'test-only','fixture','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email);
    }
    private long profile(long owner) {
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,created_at,updated_at) "
                + "VALUES(?,'fixture','SELF',2000,'FEMALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", owner);
    }
    private long medication(long profile, String name) {
        return insert("INSERT INTO my_drug(profile_id,custom_name,dose_per_time,start_date,end_date,dose_times,source) "
                + "VALUES(?,?,1,?,?,'07:00','MANUAL')", profile, name, today.minusDays(2), today.plusDays(2));
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
