package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.common.security.JwtTokenProvider;
import org.semiproject.pillbadeumserver.medication.domain.MedicationReminderRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 전용 pill_test에서만 HTTP → 서비스 → 실제 커밋 → 독립 JDBC 조회를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MedicationScheduleFlowMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Autowired MedicationReminderRepository reminders;
    private Fixture fixture;
    private LocalDate today;
    private record Fixture(long owner, long foreign, long profile, long foreignProfile, long drug, long foreignDrug, String item) {}

    @BeforeEach
    void prepareDedicatedDatabaseAndCommitIsolatedFixtures() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).as("업무 DB에는 테스트 데이터를 쓰지 않는다").isEqualTo("pill_test");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
        }
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        fixture = new TransactionTemplate(transactions).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            long owner = member("schedule-owner-" + suffix + "@example.test");
            long foreign = member("schedule-foreign-" + suffix + "@example.test");
            long profile = profile(owner), foreignProfile = profile(foreign);
            String item = "schedule-" + suffix;
            jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                    + "VALUES(?,'fixture','fixture','OTC','fixture','ROUND','WHITE','TABLET','fixture',CURRENT_TIMESTAMP)", item);
            return new Fixture(owner, foreign, profile, foreignProfile, medication(profile, item), medication(foreignProfile, item), item);
        });
    }

    @AfterEach
    void removeOnlyThisTestsCommittedFixtures() {
        if (fixture == null) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM my_drug WHERE my_drug_id IN (?,?)", fixture.drug, fixture.foreignDrug);
            jdbc.update("DELETE FROM notification_setting WHERE member_id IN (?,?)", fixture.owner, fixture.foreign);
            jdbc.update("DELETE FROM profile WHERE profile_id IN (?,?)", fixture.profile, fixture.foreignProfile);
            jdbc.update("DELETE FROM drug WHERE item_seq=?", fixture.item);
            jdbc.update("DELETE FROM member WHERE member_id IN (?,?)", fixture.owner, fixture.foreign);
        });
    }

    @Test
    void saveStopDeleteAndReregisterAreCommittedAndCalendarReflectsEachChange() throws Exception {
        mvc.perform(put(path()).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(today.minusDays(1), today.plusDays(1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doseTimes[0]").value("07:00"));
        assertThat(jdbc.queryForObject("SELECT dose_times FROM my_drug WHERE my_drug_id=?", String.class, fixture.drug))
                .isEqualTo("07:00,17:00");
        mvc.perform(get("/api/calendar").header("Authorization", bearer()).param("from", today.toString()).param("to", today.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schedules.length()").value(1))
                .andExpect(jsonPath("$.schedules[0].myDrugId").value(fixture.drug));
        mvc.perform(get("/api/calendar/doses").header("Authorization", bearer()).param("date", today.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doses.length()").value(2));

        mvc.perform(post(path() + "/stop").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endDate").value(today.toString()));
        assertThat(jdbc.queryForObject("SELECT end_date FROM my_drug WHERE my_drug_id=?", java.sql.Date.class, fixture.drug).toLocalDate())
                .isEqualTo(today);
        mvc.perform(get("/api/calendar/doses").header("Authorization", bearer()).param("date", today.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doses").isEmpty());
        mvc.perform(get("/api/calendar/doses").header("Authorization", bearer()).param("date", today.minusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doses.length()").value(2));

        mvc.perform(delete(path()).header("Authorization", bearer())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE my_drug_id=? "
                + "AND start_date IS NULL AND end_date IS NULL AND dose_times IS NULL", Long.class, fixture.drug)).isEqualTo(1L);
        mvc.perform(get(path()).header("Authorization", bearer())).andExpect(status().isNotFound());
        mvc.perform(put(path()).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body(today, today)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasSchedule").value(true));
    }

    @Test
    void invalidAndForeignWritesPreserveDataAndReminderQueriesFollowScheduleChanges() throws Exception {
        mvc.perform(put(path()).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(today.plusDays(1), today)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT end_date FROM my_drug WHERE my_drug_id=?", java.sql.Date.class, fixture.drug).toLocalDate())
                .isEqualTo(today.plusDays(2));
        String foreignPath = "/api/profiles/" + fixture.foreignProfile + "/drugs/" + fixture.foreignDrug + "/schedule";
        mvc.perform(delete(foreignPath).header("Authorization", bearer())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT dose_times FROM my_drug WHERE my_drug_id=?", String.class, fixture.foreignDrug)).isEqualTo("07:00");

        jdbc.update("INSERT INTO notification_setting(member_id,push_enabled,updated_at) VALUES(?,TRUE,CURRENT_TIMESTAMP)", fixture.owner);
        assertThat(reminders.targets(today, "07:00")).anyMatch(target -> target.memberId() == fixture.owner && target.drugCount() == 1);
        mvc.perform(post(path() + "/stop").header("Authorization", bearer())).andExpect(status().isOk());
        assertThat(reminders.targets(today.plusDays(1), "07:00")).noneMatch(target -> target.memberId() == fixture.owner);
        mvc.perform(delete(path()).header("Authorization", bearer())).andExpect(status().isNoContent());
        assertThat(reminders.targets(today, "07:00")).noneMatch(target -> target.memberId() == fixture.owner);
    }

    private String path() { return "/api/profiles/" + fixture.profile + "/drugs/" + fixture.drug + "/schedule"; }
    private String bearer() { return "Bearer " + tokens.createAccessToken(fixture.owner, "fixture@example.test", "ROLE_USER"); }
    private String body(LocalDate from, LocalDate to) {
        return "{\"startDate\":\"" + from + "\",\"endDate\":\"" + to
                + "\",\"doseTimes\":[\"17:00\",\"07:00\"],\"dosePerTime\":2,\"timing\":\"식후\"}";
    }
    private long member(String email) {
        return insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'test-only','fixture','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email);
    }
    private long profile(long owner) {
        return insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,created_at,updated_at) "
                + "VALUES(?,'fixture','SELF',2000,'FEMALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", owner);
    }
    private long medication(long profile, String item) {
        return insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,start_date,end_date,dose_times,source) "
                + "VALUES(?,?,1,?,?,'07:00','SEARCH')", profile, item, today.minusDays(2), today.plusDays(2));
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
