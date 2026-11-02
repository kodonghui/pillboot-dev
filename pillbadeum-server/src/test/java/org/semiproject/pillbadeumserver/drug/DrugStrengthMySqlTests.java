package org.semiproject.pillbadeumserver.drug;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.semiproject.pillbadeumserver.drug.service.DrugStrengthBackfill;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

/** V3-DRUG-001·002: 14 패치, 캐시 저장의 함량 칸, 기존 행 채우기를 전용 pill_test에서 확인함. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class DrugStrengthMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired DrugRepository drugs;
    @Autowired DrugStrengthBackfill backfill;

    @BeforeEach
    void schema() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
    }

    @Test
    void patch14AddsEmptyStrengthColumnsToAnExistingDrugTableAndCanRunAgain() throws Exception {
        String table = "v3_drug_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertThat(connection.getCatalog()).isEqualTo("pill_test");
            statement.execute("CREATE TABLE " + table + "(item_seq VARCHAR(100) PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            try {
                statement.executeUpdate("INSERT INTO " + table + " VALUES('1','알프람정0.25밀리그램(알프라졸람)')");
                var script = new ByteArrayResource(Files.readString(Path.of("sql/14_DRUG_STRENGTH.sql"))
                        .replace("table_name='drug'", "table_name='" + table + "'")
                        .replace("ALTER TABLE drug ", "ALTER TABLE " + table + " ")
                        .getBytes(StandardCharsets.UTF_8));
                ScriptUtils.executeSqlScript(connection, script);
                ScriptUtils.executeSqlScript(connection, script);   // 다시 적용해도 오류 없이 그대로임

                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name=? AND column_name IN ('base_name','strength_text','strength_ug','strength_unit') "
                        + "AND is_nullable='YES'", Integer.class, table)).isEqualTo(4);
                assertThat(jdbc.queryForMap("SELECT name, base_name, strength_text FROM " + table))
                        .containsEntry("name", "알프람정0.25밀리그램(알프라졸람)")
                        .containsEntry("base_name", null).containsEntry("strength_text", null);
            } finally {
                statement.execute("DROP TABLE " + table);
            }
        }
    }

    @Test
    void cachedDrugKeepsItemNameAndStoresStrengthColumns() {
        String code = "v3s-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            drugs.saveAndFlush(Drug.create(code, "알프람정0.25밀리그램(알프라졸람)", "제약", Drug.ETC, "정신신경용제", null, null, null,
                    "원형", "하양", null, null, "정제", "알프라졸람", LocalDateTime.of(2026, 11, 2, 15, 0)));

            var row = jdbc.queryForMap("SELECT name, base_name, strength_text, strength_ug, strength_unit FROM drug WHERE item_seq=?", code);
            assertThat(row).containsEntry("name", "알프람정0.25밀리그램(알프라졸람)")
                    .containsEntry("base_name", "알프람정").containsEntry("strength_text", "0.25밀리그램")
                    .containsEntry("strength_unit", "mg");
            assertThat((java.math.BigDecimal) row.get("strength_ug")).isEqualByComparingTo("250");
        } finally {
            jdbc.update("DELETE FROM drug WHERE item_seq=?", code);
        }
    }

    @Test
    void backfillFillsEmptyRowsOnceAndTheSecondRunFillsNothing() {
        String prefix = "v3b-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String[] names = {"알프람정0.25밀리그램(알프라졸람)", "아모잘탄정5/50밀리그램", "판콜에이내복액"};
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 100; i++) rows.add(new Object[]{prefix + i, names[i % 3]});
        jdbc.batchUpdate("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,?,'제약','ETC','분류','원형','하양','정제','성분','2026-11-02 15:00:00')", rows);
        try {
            var first = backfill.run();
            var second = backfill.run();

            assertThat(first.total()).isGreaterThanOrEqualTo(100);
            assertThat(second.total()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drug WHERE item_seq LIKE ? AND base_name IS NULL",
                    Integer.class, prefix + "%")).isZero();
            assertThat(jdbc.queryForMap("SELECT base_name, strength_text, strength_ug, strength_unit FROM drug WHERE item_seq=?", prefix + 0))
                    .containsEntry("base_name", "알프람정").containsEntry("strength_text", "0.25밀리그램").containsEntry("strength_unit", "mg");
            assertThat(jdbc.queryForMap("SELECT base_name, strength_text, strength_ug FROM drug WHERE item_seq=?", prefix + 1))
                    .containsEntry("base_name", "아모잘탄정").containsEntry("strength_text", "5/50밀리그램").containsEntry("strength_ug", null);
            assertThat(jdbc.queryForMap("SELECT base_name, strength_text FROM drug WHERE item_seq=?", prefix + 2))
                    .containsEntry("base_name", "판콜에이내복액").containsEntry("strength_text", null);
        } finally {
            jdbc.update("DELETE FROM drug WHERE item_seq LIKE ?", prefix + "%");
        }
    }
}
