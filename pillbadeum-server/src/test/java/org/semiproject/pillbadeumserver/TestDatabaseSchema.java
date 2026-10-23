package org.semiproject.pillbadeumserver;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

/** 최신01만으로 전용 테스트DB를 준비함. 신규 설치가 과거 패치에 의존하지 않는지 검증함. */
public final class TestDatabaseSchema {
    private TestDatabaseSchema() {}

    public static void prepare(Connection connection) throws Exception {
        if (!"pill_test".equals(connection.getCatalog())) {
            throw new IllegalArgumentException("Only the dedicated pill_test database may be modified");
        }
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/01_PILLBADEUM_DB_SCRIPT.sql"));
    }

    /** 최신01과 컬럼명이 일치하는02를 전용 테스트DB에만 적용함. */
    public static void seed(Connection connection) throws Exception {
        if (!"pill_test".equals(connection.getCatalog())) {
            throw new IllegalArgumentException("Only the dedicated pill_test database may be modified");
        }
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/13_BASE_CONSENT.sql"));
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/02_PILLBADEUM_TEST_DATA.sql"));
    }
}
