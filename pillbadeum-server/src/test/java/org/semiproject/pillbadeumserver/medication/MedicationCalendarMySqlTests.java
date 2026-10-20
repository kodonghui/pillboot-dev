package org.semiproject.pillbadeumserver.medication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 명시적으로 켠 전용 pill_test에서만 SQL 경계·소유권·NULL 처리를 검증하고 fixture는 롤백한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MedicationCalendarMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MedicationRepository reads;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 19);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);

    @Test
    void sqlIncludesOverlappingOwnedSchedulesAndExcludesDeletedForeignAndOutsideSchedules() throws Exception {
        // MySQL DDL의 자동 커밋이 fixture에 영향을 주지 않도록 트랜잭션을 시작하기 전에 실행한다.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATABASE()")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).as("Only the dedicated pill_test database may be modified").isEqualTo("pill_test");
            org.semiproject.pillbadeumserver.TestDatabaseSchema.prepare(connection);
        }

        var jdbc = new JdbcTemplate(dataSource);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            String suffix = UUID.randomUUID().toString();
            long owner = member(jdbc, "calendar-owner-" + suffix + "@example.test");
            long foreign = member(jdbc, "calendar-foreign-" + suffix + "@example.test");
            long firstProfile = profile(jdbc, owner, "본인");
            long secondProfile = profile(jdbc, owner, "부모님");
            long foreignProfile = profile(jdbc, foreign, "다른회원");
            String itemSeq = "calendar-" + suffix;
            drug(jdbc, itemSeq);

            long endsAtFrom = medication(jdbc, firstProfile, itemSeq, FROM.minusDays(5), FROM, "07:00");
            long covering = medication(jdbc, firstProfile, drug(jdbc,itemSeq+"-cover"), FROM.minusDays(5), TO.plusDays(5), "11:00,17:00");
            long startsAtTo = medication(jdbc, secondProfile, itemSeq, TO, TO.plusDays(5), "17:00");
            long deleted = medication(jdbc, firstProfile, drug(jdbc,itemSeq+"-empty"), null, null, null);
            medication(jdbc, firstProfile, drug(jdbc,itemSeq+"-before"), FROM.minusDays(5), FROM.minusDays(1), "07:00");
            medication(jdbc, secondProfile, drug(jdbc,itemSeq+"-after"), TO.plusDays(1), TO.plusDays(5), "07:00");
            medication(jdbc, foreignProfile, itemSeq, FROM, TO, "07:00");

            assertThat(reads.calendar(owner, FROM, TO)).extracting(MedicationTarget::myDrugId)
                    .containsExactly(endsAtFrom, covering, startsAtTo);
            assertThat(reads.calendar(owner, FROM, FROM)).extracting(MedicationTarget::myDrugId)
                    .containsExactly(endsAtFrom, covering);
            assertThat(reads.calendar(owner, TO, TO)).extracting(MedicationTarget::myDrugId)
                    .containsExactly(covering, startsAtTo);
            assertThat(reads.find(foreign, firstProfile, covering)).isEmpty();
            var unscheduled = reads.find(owner, firstProfile, deleted).orElseThrow();
            assertThat(unscheduled.hasSchedule()).isFalse();
            assertThat(unscheduled.doseTimes()).isEmpty();
            assertThat(reads.drugs(owner, firstProfile)).extracting(MedicationTarget::myDrugId).contains(deleted);

            jdbc.update("UPDATE my_drug SET start_date=NULL,end_date=NULL,dose_times=NULL WHERE my_drug_id=?", covering);
            assertThat(reads.calendar(owner, FROM, TO)).extracting(MedicationTarget::myDrugId)
                    .containsExactly(endsAtFrom, startsAtTo);
            assertThat(reads.find(owner, firstProfile, covering)).isPresent();
        });
    }

    private long member(JdbcTemplate jdbc, String email) {
        return insert(jdbc, "INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'test-only','fixture','ROLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email);
    }

    private long profile(JdbcTemplate jdbc, long memberId, String name) {
        return insert(jdbc, "INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,created_at,updated_at) "
                + "VALUES(?,?,'SELF',2000,'FEMALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", memberId, name);
    }

    private String drug(JdbcTemplate jdbc, String itemSeq) {
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,'fixture','fixture','OTC','fixture','ROUND','WHITE','TABLET','fixture',CURRENT_TIMESTAMP)",itemSeq);
        return itemSeq;
    }

    private long medication(JdbcTemplate jdbc, long profileId, String itemSeq, LocalDate start, LocalDate end, String times) {
        return insert(jdbc, "INSERT INTO my_drug(profile_id,item_seq,dose_per_time,start_date,end_date,dose_times,source) "
                + "VALUES(?,?,1,?,?,?,'SEARCH')", profileId, itemSeq, start, end, times);
    }

    private long insert(JdbcTemplate jdbc, String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
