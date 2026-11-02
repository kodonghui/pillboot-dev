package org.semiproject.pillbadeumserver.drug.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** V3-DRUG-002: 채우기가 읽은 뒤 새로 받은 행의 공식 정보를 되돌리지 않는지 전용 pill_test에서 확인함. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class DrugStrengthBackfillMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired DrugStrengthBackfill backfill;

    @BeforeEach
    void schema() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
    }

    @Test
    void staleSnapshotLeavesARefreshedRowAndOnlyStrengthColumnsChange() {
        String code = "v3r-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at,cancelled) "
                + "VALUES(?,'알프람정0.5밀리그램(알프라졸람)','새제약','ETC','분류','원형','하양','정제','새 성분','2026-11-02 16:00:00',1)", code);
        try {
            // 채우기가 옛 이름을 읽은 사이 서비스 서버가 같은 행을 새로 받은 경우
            var stale = backfill.fill(List.of(new DrugStrengthBackfill.Row(code, "알프람정0.25밀리그램(알프라졸람)")));

            assertThat(stale.total()).isZero();
            assertThat(jdbc.queryForMap("SELECT name, maker, material, cancelled, synced_at, base_name FROM drug WHERE item_seq=?", code))
                    .containsEntry("name", "알프람정0.5밀리그램(알프라졸람)").containsEntry("maker", "새제약")
                    .containsEntry("material", "새 성분").containsEntry("cancelled", true)
                    .containsEntry("base_name", null)
                    .containsEntry("synced_at", LocalDateTime.of(2026, 11, 2, 16, 0));

            var current = backfill.fill(List.of(new DrugStrengthBackfill.Row(code, "알프람정0.5밀리그램(알프라졸람)")));

            assertThat(current.filled()).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT name, material, cancelled, synced_at, base_name, strength_text, strength_unit FROM drug WHERE item_seq=?", code))
                    .containsEntry("name", "알프람정0.5밀리그램(알프라졸람)").containsEntry("material", "새 성분")
                    .containsEntry("cancelled", true).containsEntry("base_name", "알프람정")
                    .containsEntry("strength_text", "0.5밀리그램").containsEntry("strength_unit", "mg")
                    .containsEntry("synced_at", LocalDateTime.of(2026, 11, 2, 16, 0));
            // 이미 채운 행은 같은 이름으로 다시 와도 건드리지 않음
            assertThat(backfill.fill(List.of(new DrugStrengthBackfill.Row(code, "알프람정0.5밀리그램(알프라졸람)"))).total()).isZero();
        } finally {
            jdbc.update("DELETE FROM drug WHERE item_seq=?", code);
        }
    }
}
