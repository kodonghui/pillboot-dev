package org.semiproject.pillbadeumserver.chat;

import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.chat.service.*;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugCreateRequest;
import org.semiproject.pillbadeumserver.drug.service.MyDrugRegisterService;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.image.service.PillDrugRegistrationService;
import org.semiproject.pillbadeumserver.image.domain.PillDrugRegistrationRequest;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 등록 커밋/롤백과 비동기 DUR 캐시를 확인한다. 사용자 DB나 실제 공공 API는 사용하지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class RegistrationDurMySqlTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MyDrugRegisterService registration;
    @Autowired PillDrugRegistrationService photoRegistration;
    @Autowired DrugRepository drugs;
    @Autowired MyDrugRepository myDrugs;
    @Autowired PlatformTransactionManager transactions;
    @Autowired GlobalExceptionHandler errors;
    @MockitoBean DurLookupService lookup;
    @MockitoBean ProfileAccessChecker access;
    @MockitoBean DrugSyncService sync;
    @MockitoSpyBean DurCacheStore cache;
    private long member, profile;
    private String code;
    private CountDownLatch release, cacheFinished;

    @BeforeEach void prepare() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        try (var connection = dataSource.getConnection()) { TestDatabaseSchema.prepare(connection); }
        // 픽스처 생성 중 실패해도 정리 메서드가 실행될 수 있도록 대기 장치를 먼저 준비한다.
        release = new CountDownLatch(1); cacheFinished = new CountDownLatch(1);
        code = "prefetch-" + UUID.randomUUID();
        member = insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) VALUES(?,'synthetic','fixture','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID()+"@example.test");
        profile = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,profile_pregnant,created_at,updated_at) VALUES(?,'fixture','SELF',2000,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member);
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) VALUES(?,'synthetic','synthetic','OTC','synthetic','ROUND','WHITE','TABLET','synthetic',CURRENT_TIMESTAMP)", code);
        when(sync.getOrSync(code)).thenReturn(drugs.findById(code).orElseThrow());
        var completed = cacheFinished;
        doAnswer(invocation -> { try { return invocation.callRealMethod(); } finally { completed.countDown(); } }).when(cache).refresh(anyList(), any());
        when(lookup.collectForPrefetch(anyList(), anySet(), any())).thenAnswer(invocation ->
                List.of(new DurLookup(code, DurLookup.Type.AGE, false, List.of(), LocalDate.now(TimeZoneConfig.ZONE))));
    }
    @AfterEach void clean() {
        release.countDown();
        jdbc.update("DELETE FROM my_drug WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM dur_warning WHERE item_seq=?", code);
        jdbc.update("DELETE FROM dur_interaction WHERE item_seq2=?", code);
        jdbc.update("DELETE FROM profile WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM member WHERE member_id=?", member);
        jdbc.update("DELETE FROM drug WHERE item_seq=?", code);
    }
    @Test void lookupStartsAfterCommitAndRegistrationReturnsWhileProviderIsStillWaiting() throws Exception {
        var started = new CountDownLatch(1);
        when(lookup.collectForPrefetch(anyList(), anySet(), any())).thenAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
            started.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(new DurLookup(code, DurLookup.Type.AGE, true, List.of(), LocalDate.now(TimeZoneConfig.ZONE)));
        });
        var saved = registration.saveAll(profile, DrugSource.SEARCH, items());
        assertThat(saved).hasSize(1);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(cacheFinished.getCount()).isEqualTo(1);
        release.countDown();
        assertThat(cacheFinished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
    }
    @Test void rolledBackRegistrationNeverStartsPrefetch() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            registration.saveAll(profile, DrugSource.SEARCH, items());
            verifyNoInteractions(lookup);
            status.setRollbackOnly();
        });
        verify(lookup, after(150).never()).collectForPrefetch(anyList(), anySet(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isZero();
    }
    @Test void registrationKeepsCalendarDatesInThePhysicalDatabase() throws Exception {
        // HTTP 응답의 메모리 값만 검사하면 JDBC가 날짜를 하루 앞당겨 저장하는 오류를 놓칠 수 있다.
        var day = LocalDate.of(2026, 10, 20);
        var saved = registration.saveAll(profile, DrugSource.SEARCH, List.of(new MyDrugCreateRequest.Item(
                code, 1, null, day, day.plusDays(1), List.of(LocalTime.of(8, 0))))).getFirst();
        assertThat(cacheFinished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(start_date, '%Y-%m-%d') FROM my_drug WHERE profile_id=?",
                String.class, profile)).isEqualTo("2026-10-20");
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(end_date, '%Y-%m-%d') FROM my_drug WHERE profile_id=?",
                String.class, profile)).isEqualTo("2026-10-21");
        var reloaded = myDrugs.findWithDrugById(saved.myDrugId()).orElseThrow();
        assertThat(reloaded.getStartDate()).isEqualTo(day);
        assertThat(reloaded.getEndDate()).isEqualTo(day.plusDays(1));
        // ERD의 DATETIME(0)은 소수초를 반올림할 수 있다. 시간대 오차와 최대 1초 정밀도 차이를 구분한다.
        assertThat(reloaded.getRegisteredAt()).isCloseTo(saved.registeredAt(), within(1, ChronoUnit.SECONDS));
    }
    @Test void failedProviderPreservesTheCommittedRegistrationAndPreviousCache() throws Exception {
        jdbc.update("INSERT INTO dur_warning(item_seq,type,ingredient,effect,content,synced_at) VALUES(?,'연령','synthetic','synthetic','previous-cache',CURRENT_TIMESTAMP)", code);
        registration.saveAll(profile, DrugSource.SEARCH, items());
        assertThat(cacheFinished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM dur_warning WHERE item_seq=?", String.class, code)).isEqualTo("previous-cache");
    }
    @Test void legacySearchRegistrationAlsoStartsPrefetchAfterRepositoryCommit() throws Exception {
        when(lookup.collectForPrefetch(anyList(), anySet(), any())).thenAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
            return List.of(new DurLookup(code, DurLookup.Type.AGE, false, List.of(), LocalDate.now(TimeZoneConfig.ZONE)));
        });
        var day = LocalDate.now(TimeZoneConfig.ZONE);
        var response = photoRegistration.register(profile, new PillDrugRegistrationRequest(code, DrugSource.SEARCH,
                1, null, day, day.plusDays(1), List.of(LocalTime.of(7, 0))));
        assertThat(response.itemSeq()).isEqualTo(code);
        assertThat(cacheFinished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
    }
    @Test void rejectedDuplicateDoesNotStartAnotherPrefetch() throws Exception {
        registration.saveAll(profile, DrugSource.SEARCH, items());
        assertThat(cacheFinished.await(3, TimeUnit.SECONDS)).isTrue();
        clearInvocations(lookup);
        var day = LocalDate.now(TimeZoneConfig.ZONE);
        assertThrows(BusinessException.class, () -> photoRegistration.register(profile,
                new PillDrugRegistrationRequest(code, DrugSource.SEARCH, 1, null, day, day,
                        List.of(LocalTime.of(7, 0)))));
        verify(lookup, after(150).never()).collectForPrefetch(anyList(), anySet(), any());
    }
    @Test void legacyRegistrationCannotBypassPillResultValidation() {
        var day = LocalDate.now(TimeZoneConfig.ZONE);
        for (var source : List.of(DrugSource.PILL_PHOTO, DrugSource.PILL_CLUES)) {
            var rejected = assertThrows(BusinessException.class, () -> photoRegistration.register(profile,
                    new PillDrugRegistrationRequest(code, source, 1, null, day, day,
                            List.of(LocalTime.of(7, 0)))));
            assertThat(rejected.getErrorCode()).isEqualTo(org.semiproject.pillbadeumserver.common.exception.ErrorCode.INVALID_INPUT);
        }
        verify(sync, never()).getOrSync(anyString());
        verify(lookup, after(150).never()).collectForPrefetch(anyList(), anySet(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isZero();
    }
    @Test void concurrentRegistrationsKeepOneRowAndUseTheSameConflictContract() throws Exception {
        var ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> attempt = () -> {
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        // 실제 MySQL REPEATABLE READ에서 두 요청 모두 저장 전 빈 목록을 읽도록 맞춘다.
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isZero();
                        ready.countDown();
                        try { assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                        registration.saveAll(profile, DrugSource.SEARCH, items());
                    });
                    return 201;
                } catch (DataIntegrityViolationException exception) {
                    var response = errors.handleDataConflict(exception);
                    assertThat(response.getBody().getCode()).isEqualTo("CONFLICT");
                    return response.getStatusCode().value();
                }
            };
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(cacheFinished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM my_drug WHERE profile_id=?", Integer.class, profile)).isEqualTo(1);
        verify(lookup, times(1)).collectForPrefetch(anyList(), anySet(), any());
    }
    private List<MyDrugCreateRequest.Item> items() {
        var day = LocalDate.now(TimeZoneConfig.ZONE);
        return List.of(new MyDrugCreateRequest.Item(code, 1, null, day, day.plusDays(1), List.of(LocalTime.of(7, 0))));
    }
    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> { var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);return statement; }, key);
        return key.getKey().longValue();
    }
}
