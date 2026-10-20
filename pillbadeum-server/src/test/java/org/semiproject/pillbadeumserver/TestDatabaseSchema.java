package org.semiproject.pillbadeumserver;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/** 원격의 원본 SQL은 그대로 두고 전용 테스트 DB에 원본 + 복약지도 변경을 순서대로 적용한다. */
public final class TestDatabaseSchema {
    private TestDatabaseSchema() {}

    public static void prepare(Connection connection) throws Exception {
        if (!"pill_test".equals(connection.getCatalog())) {
            throw new IllegalArgumentException("Only the dedicated pill_test database may be modified");
        }
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/03_MEDICATION_UPDATE.sql"));
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/04_MY_DRUG_COMPOSITE_UNIQUE.sql"));
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/05_PHOTO_CANDIDATE_LINKS.sql"));
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/06_DRUG_CACHE_METADATA.sql"));
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/07_DOSE_CONFIRMATION.sql"));
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/08_CHAT_DUR_SNAPSHOT.sql"));
    }

    /** 이미 이름 변경된 테스트 DB에도 원본 02의 샘플을 넣을 수 있게 메모리에서만 이름을 대응한다. */
    public static void seed(Connection connection) throws Exception {
        if (!"pill_test".equals(connection.getCatalog())) {
            throw new IllegalArgumentException("Only the dedicated pill_test database may be modified");
        }
        String seed = Files.readString(Path.of("sql/02_PILLBADEUM_TEST_DATA.sql"), StandardCharsets.UTF_8)
                .replace("realationship_type", "relationship_type").replace("matrial", "material");
        ScriptUtils.executeSqlScript(connection, new ByteArrayResource(seed.getBytes(StandardCharsets.UTF_8)));
    }
}
