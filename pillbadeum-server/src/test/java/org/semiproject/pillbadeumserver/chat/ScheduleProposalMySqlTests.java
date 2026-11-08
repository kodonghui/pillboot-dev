package org.semiproject.pillbadeumserver.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.semiproject.pillbadeumserver.TestDatabaseSchema;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalConfirmRequest;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse.ScheduleValues;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.chat.service.ChatDeadline;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalConflictException;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalService;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalStore;
import org.semiproject.pillbadeumserver.chat.service.QuestionStore;
import org.semiproject.pillbadeumserver.chat.service.ScheduleProposalService;
import org.semiproject.pillbadeumserver.chat.service.ScheduleSetInterpreter;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.security.CustomUserDetails;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.ParsedCommand;
import org.semiproject.pillbadeumserver.medication.service.MedicationVoiceInterpreter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V3-CHAT-007(TASK-029): 일정 제안 만들기·확정·취소를 전용 pill_test에서 확인함. 카드를 만들어도 확정 전에는 일정이 그대로이고,
 * 카드를 만든 뒤 일정이 바뀌었거나 약을 지웠으면 409 SCHEDULE_CHANGED이며 아무것도 저장하지 않음. 해석기는 가짜로 둠.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class ScheduleProposalMySqlTests {
    private static final String NAME = "가상혈압정5밀리그램";
    private static final String QUESTION = "가상혈압정 아침 8시 한 알로 바꿔 줘";
    private static final Map<String, Object> MONTH = Map.of("start_date", LocalDate.of(2026, 11, 1),
            "end_date", LocalDate.of(2026, 11, 30), "dose_times", "07:00,19:00", "dose_per_time", 2, "timing", "식후 30분");
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionStore questions;
    @Autowired ChatProposalStore store;
    @Autowired ChatProposalService service;
    @Autowired ScheduleProposalService schedules;
    @MockitoBean MedicationVoiceInterpreter interpreter;
    @MockitoBean ScheduleSetInterpreter setInterpreter;
    private long member, other, profile, session, drug, proposalId;
    private String code;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestDatabaseSchema.prepare(connection);
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("pill_test");
        code = "schedule-" + UUID.randomUUID().toString().substring(0, 8);
        member = memberRow();
        other = memberRow();
        profile = insert("INSERT INTO profile(member_id,profile_name,relationship_type,profile_birth_year,profile_gender,"
                + "profile_pregnant,created_at,updated_at) VALUES(?,'fixture','SELF',2000,'FEMALE',FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", member);
        session = insert("INSERT INTO chat_session(member_id,profile_id,created_at,snap_birth_year,snap_gender) "
                + "VALUES(?,?,CURRENT_TIMESTAMP,2000,'FEMALE')", member, profile);
        jdbc.update("INSERT INTO drug(item_seq,name,maker,etc_otc,class_name,shape,color,form,material,synced_at) "
                + "VALUES(?,?,'시험제약','ETC','synthetic','ROUND','WHITE','TABLET','synthetic',CURRENT_TIMESTAMP)", code, NAME);
        drug = insert("INSERT INTO my_drug(profile_id,item_seq,dose_per_time,timing,start_date,end_date,dose_times,source) "
                + "VALUES(?,?,2,'식후 30분','2026-11-01','2026-11-30','07:00,19:00','SEARCH')", profile, code);
        when(interpreter.parse(any(), any(), any())).thenReturn(
                new ParsedCommand("CREATE_SCHEDULE", null, null, null, null, List.of("08:00"), null, 1, null));
        proposalId = propose(NAME).proposal().proposalId();
        login(member);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        // 제안·일정 제안 행은 메시지를 지우면 함께 지워짐(ON DELETE CASCADE).
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", session);
        jdbc.update("DELETE FROM chat_session WHERE session_id=?", session);
        jdbc.update("DELETE FROM my_drug WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM profile WHERE profile_id=?", profile);
        jdbc.update("DELETE FROM member WHERE member_id IN (?,?)", member, other);
        jdbc.update("DELETE FROM drug WHERE item_seq=?", code);
    }

    @Test
    void cardShowsNowAndAfterWithoutChangingTheSchedule() {
        login(member);
        var card = service.get(proposalId);
        assertThat(card.kind()).isEqualTo(ChatProposalResponse.Kind.SCHEDULE);
        assertThat(card.status()).isEqualTo(ChatProposalResponse.Status.PENDING);
        assertThat(card.items()).isEmpty();
        var month = new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of("07:00", "19:00"), 2, "식후 30분");
        assertThat(card.schedule()).isEqualTo(new ChatProposalResponse.Schedule(drug, NAME, month,
                new ScheduleValues(month.startDate(), month.endDate(), List.of("08:00"), 1, "식후 30분"),
                "하루 2번에서 1번으로 바뀌어요."));
        assertThat(card.schedules()).as("한 약 카드도 schedules에 같은 줄 하나").containsExactly(card.schedule());
        assertThat(schedule()).as("확정 전에는 일정이 그대로").isEqualTo(MONTH);
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE message_id=?", String.class, card.messageId()))
                .isEqualTo("일정을 바꾸는 카드를 만들었어요. 카드에서 지금 일정과 바뀐 뒤 일정을 확인하고 확정해 주세요. "
                        + "확정하기 전에는 일정이 그대로이고, 카드는 30분 동안 쓸 수 있어요.");
        assertThat(store.findByMessages(List.of(card.messageId()), member)).as("상담 기록에도 같은 카드")
                .containsEntry(card.messageId(), card);
    }

    @Test
    void confirmSavesTheAfterScheduleOnceAndARepeatGetsTheFirstResult() {
        var confirmed = service.confirm(proposalId, null);
        assertThat(confirmed.status()).isEqualTo(ChatProposalResponse.Status.CONFIRMED);
        assertThat(confirmed.decidedAt()).isNotNull();
        assertThat(schedule()).containsEntry("dose_times", "08:00").containsEntry("dose_per_time", 1)
                .containsEntry("start_date", LocalDate.of(2026, 11, 1)).containsEntry("end_date", LocalDate.of(2026, 11, 30))
                .containsEntry("timing", "식후 30분");
        assertThat(jdbc.queryForObject("SELECT dose_confirmed FROM my_drug WHERE my_drug_id=?", Boolean.class, drug)).isTrue();

        assertThatThrownBy(() -> service.confirm(proposalId, null))
                .isInstanceOfSatisfying(ChatProposalConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_ALREADY_CONFIRMED);
                    assertThat(conflict.getProposal()).isEqualTo(confirmed);
                });
        assertThat(service.get(proposalId)).isEqualTo(confirmed);
    }

    @Test
    void scheduleChangedAfterTheCardIsRefusedAndNothingIsSaved() {
        jdbc.update("UPDATE my_drug SET dose_times='09:00,21:00' WHERE my_drug_id=?", drug);
        assertThatThrownBy(() -> service.confirm(proposalId, null))
                .isInstanceOfSatisfying(ChatProposalConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.SCHEDULE_CHANGED);
                    assertThat(conflict.getProposal().status()).isEqualTo(ChatProposalResponse.Status.PENDING);
                });
        assertThat(storedStatus()).isEqualTo("PENDING");
        assertThat(schedule()).containsEntry("dose_times", "09:00,21:00").containsEntry("dose_per_time", 2);

        // 값으로 맞춰 보므로, 다시 카드의 '지금'과 같아지면 확정할 수 있음
        jdbc.update("UPDATE my_drug SET dose_times='19:00,07:00' WHERE my_drug_id=?", drug);
        assertThat(service.confirm(proposalId, null).status()).isEqualTo(ChatProposalResponse.Status.CONFIRMED);
        assertThat(schedule()).containsEntry("dose_times", "08:00");
    }

    @Test
    void deletedDrugLeavesACardThatCannotBeConfirmed() {
        jdbc.update("DELETE FROM my_drug WHERE my_drug_id=?", drug);
        var card = service.get(proposalId);
        assertThat(card.schedule().myDrugId()).isNull();
        assertThat(card.schedule().drugName()).isNull();
        assertThatThrownBy(() -> service.confirm(proposalId, null)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.SCHEDULE_CHANGED));
        assertThat(storedStatus()).isEqualTo("PENDING");
    }

    @Test
    void itemNumbersAreRefusedForAScheduleCard() {
        assertThatThrownBy(() -> service.confirm(proposalId, new ChatProposalConfirmRequest(List.of(1))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(service.confirm(proposalId, new ChatProposalConfirmRequest(null)).status())
                .as("itemNos 없는 본문은 본문 없음과 같음").isEqualTo(ChatProposalResponse.Status.CONFIRMED);
    }

    @Test
    void cancelLeavesTheScheduleAndBlocksConfirm() {
        var cancelled = service.cancel(proposalId);
        assertThat(cancelled.status()).isEqualTo(ChatProposalResponse.Status.CANCELLED);
        assertThatThrownBy(() -> service.confirm(proposalId, null)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_CANCELLED));
        assertThat(schedule()).isEqualTo(MONTH);
    }

    @Test
    void lapsedCardIsRefused() {
        jdbc.update("UPDATE chat_proposal SET created_at = created_at - INTERVAL 31 MINUTE, "
                + "expires_at = expires_at - INTERVAL 31 MINUTE WHERE proposal_id=?", proposalId);
        assertThatThrownBy(() -> service.confirm(proposalId, null)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.PROPOSAL_EXPIRED));
        assertThat(schedule()).isEqualTo(MONTH);
    }

    @Test
    void twoConfirmsAtOnceSaveOnce() throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Object>>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    login(member);
                    start.await();
                    try {
                        return service.confirm(proposalId, null);
                    } catch (ChatProposalConflictException conflict) {
                        return conflict.getCode();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();
            var results = new ArrayList<Object>();
            for (var future : futures) results.add(future.get());
            assertThat(results).filteredOn(ChatProposalResponse.class::isInstance).hasSize(1);
            assertThat(results).contains(ChatProposalConflictException.Code.PROPOSAL_ALREADY_CONFIRMED);
        }
        assertThat(schedule()).containsEntry("dose_times", "08:00");
    }

    @Test
    void otherMembersCardIsTheSameAsAMissingOne() {
        login(other);
        assertThatThrownBy(() -> service.get(proposalId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.confirm(proposalId, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(proposalId)).isInstanceOf(NotFoundException.class);
        assertThat(storedStatus()).isEqualTo("PENDING");
        assertThat(schedule()).isEqualTo(MONTH);
    }

    @Test
    void handEnteredDrugWithoutAScheduleGetsTodayAndTheSaidTime() {
        long custom = insert("INSERT INTO my_drug(profile_id,custom_name,dose_per_time,timing,source) "
                + "VALUES(?,'가상 영양제',1,NULL,'MANUAL')", profile);
        var card = propose("가상 영양제").proposal();
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertThat(card.schedule()).isEqualTo(new ChatProposalResponse.Schedule(custom, "가상 영양제",
                new ScheduleValues(null, null, null, 1, null), new ScheduleValues(today, today, List.of("08:00"), 1, null)));
        login(member);
        service.confirm(card.proposalId(), null);
        assertThat(schedule(custom))
                .containsEntry("start_date", today).containsEntry("end_date", today).containsEntry("dose_times", "08:00");
    }

    @Test
    void anUnconfirmedDoseIsEmptyNowAndConfirmingItElsewhereChangesTheSchedule() {
        jdbc.update("UPDATE my_drug SET dose_per_time=1, dose_confirmed=FALSE WHERE my_drug_id=?", drug);
        when(interpreter.parse(any(), any(), any())).thenReturn(
                new ParsedCommand("CREATE_SCHEDULE", null, null, null, null, List.of("08:00"), null, null, null));
        var card = propose(NAME).proposal();
        assertThat(card.schedule().current().dosePerTime()).as("내 약 응답처럼 확인되지 않은 개수는 비움").isNull();
        assertThat(card.schedule().proposed().dosePerTime()).as("말하지 않았으면 저장된 개수").isEqualTo(1);
        login(member);

        // 카드를 만든 뒤 다른 화면에서 같은 개수를 확인함
        jdbc.update("UPDATE my_drug SET dose_confirmed=TRUE WHERE my_drug_id=?", drug);
        assertThatThrownBy(() -> service.confirm(card.proposalId(), null)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.SCHEDULE_CHANGED));

        jdbc.update("UPDATE my_drug SET dose_confirmed=FALSE WHERE my_drug_id=?", drug);
        assertThat(service.confirm(card.proposalId(), null).status()).isEqualTo(ChatProposalResponse.Status.CONFIRMED);
        assertThat(schedule()).containsEntry("dose_times", "08:00").containsEntry("dose_per_time", 1);
        assertThat(jdbc.queryForObject("SELECT dose_confirmed FROM my_drug WHERE my_drug_id=?", Boolean.class, drug)).isTrue();
    }

    @Test
    void severalDrugsBecomeOneCardAndConfirmSavesThemTogether() {
        // 2026-11-08 카톡 기록: '나 먹는 약들 3일간 오늘부터 아침 점심 저녁 한 알씩, 시럽은 20미리'
        long syrup = insert("INSERT INTO my_drug(profile_id,custom_name,dose_per_time,timing,source) "
                + "VALUES(?,'가상 시럽',1,NULL,'MANUAL')", profile);
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        everyDrugFor3Days(today, syrup);
        var answer = proposeAll();
        var card = answer.proposal();
        var month = new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of("07:00", "19:00"), 2, "식후 30분");
        var meals = List.of("07:00", "12:00", "17:00");
        assertThat(card.schedules()).containsExactly(
                new ChatProposalResponse.Schedule(drug, NAME, month,
                        new ScheduleValues(today, today.plusDays(2), meals, 1, "식후 30분"), "하루 2번에서 3번으로 바뀌어요."),
                new ChatProposalResponse.Schedule(syrup, "가상 시럽", new ScheduleValues(null, null, null, 1, null),
                        new ScheduleValues(today, today.plusDays(2), meals, 1, "1회 20mL"), "1회 20mL는 복용 때 칸에 적었어요."));
        assertThat(card.schedule()).as("이전 화면·다리가 읽는 칸은 첫 줄").isEqualTo(card.schedules().getFirst());
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE message_id=?", String.class, card.messageId()))
                .startsWith("약 2개의 일정을 바꾸는 카드를 만들었어요.");
        assertThat(store.findByMessages(List.of(card.messageId()), member)).as("상담 기록에도 같은 카드")
                .containsEntry(card.messageId(), card);
        assertThat(schedule()).as("확정 전에는 일정이 그대로").isEqualTo(MONTH);
        assertThat(schedule(syrup).get("start_date")).isNull();

        login(member);
        assertThat(service.confirm(card.proposalId(), null).status()).isEqualTo(ChatProposalResponse.Status.CONFIRMED);
        assertThat(schedule()).containsEntry("start_date", today).containsEntry("end_date", today.plusDays(2))
                .containsEntry("dose_times", "07:00,12:00,17:00").containsEntry("dose_per_time", 1).containsEntry("timing", "식후 30분");
        assertThat(schedule(syrup)).containsEntry("start_date", today).containsEntry("dose_times", "07:00,12:00,17:00")
                .containsEntry("timing", "1회 20mL");
    }

    @Test
    void oneChangedDrugKeepsTheWholeCardPendingAndSavesNothing() {
        long syrup = insert("INSERT INTO my_drug(profile_id,custom_name,dose_per_time,timing,source) "
                + "VALUES(?,'가상 시럽',1,NULL,'MANUAL')", profile);
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        everyDrugFor3Days(today, syrup);
        var card = proposeAll().proposal();
        login(member);
        jdbc.update("UPDATE my_drug SET dose_per_time=2 WHERE my_drug_id=?", syrup);
        assertThatThrownBy(() -> service.confirm(card.proposalId(), null)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.SCHEDULE_CHANGED));
        assertThat(jdbc.queryForObject("SELECT status FROM chat_proposal WHERE proposal_id=?", String.class, card.proposalId()))
                .isEqualTo("PENDING");
        assertThat(schedule()).as("바뀌지 않은 약도 저장하지 않음").isEqualTo(MONTH);
        assertThat(schedule(syrup).get("start_date")).isNull();

        jdbc.update("DELETE FROM my_drug WHERE my_drug_id=?", syrup);
        var read = service.get(card.proposalId());
        assertThat(read.schedules().get(1).myDrugId()).as("지운 약 줄은 확정할 수 없음").isNull();
        assertThatThrownBy(() -> service.confirm(card.proposalId(), null)).isInstanceOfSatisfying(ChatProposalConflictException.class,
                conflict -> assertThat(conflict.getCode()).isEqualTo(ChatProposalConflictException.Code.SCHEDULE_CHANGED));
        assertThat(schedule()).isEqualTo(MONTH);
    }

    /** 일정 여러 약 해석기 가짜: 모든 약에 오늘부터 3일, 하루 3번 1개씩. 시럽은 1회 20mL. */
    private void everyDrugFor3Days(LocalDate today, long syrup) {
        when(setInterpreter.parse(any(), any(), any())).thenAnswer(call -> {
            List<MedicationTarget> targets = call.getArgument(2);
            return new ScheduleSetInterpreter.Result("CREATE_SCHEDULE", targets.stream().map(target ->
                    new ScheduleSetInterpreter.DrugCommand(new ParsedCommand("CREATE_SCHEDULE", target.myDrugId(),
                            today.toString(), null, 3, null, 3, 1, null), target.myDrugId() == syrup ? "20mL" : null)).toList());
        });
    }

    private QuestionResponse.Answer proposeAll() {
        String text = "나 먹는 약들 3일간 오늘부터 아침 점심 저녁 한 알씩, 시럽은 20미리";
        var question = questions.save(session, member, profile, text);
        return schedules.propose(session, member, profile, question, text, List.of(), true,
                ChatDeadline.after(Duration.ofSeconds(30)), false);
    }

    private QuestionResponse.Answer propose(String target) {
        var question = questions.save(session, member, profile, QUESTION);
        return schedules.propose(session, member, profile, question, QUESTION, target, ChatDeadline.after(Duration.ofSeconds(30)), false);
    }

    private Map<String, Object> schedule() {
        return schedule(drug);
    }

    /** 약 행의 일정 칸. 날짜는 LocalDate로 읽음. */
    private Map<String, Object> schedule(long myDrugId) {
        return jdbc.queryForObject("SELECT start_date, end_date, dose_times, dose_per_time, timing FROM my_drug WHERE my_drug_id=?",
                (row, rowNum) -> {
                    var values = new HashMap<String, Object>();
                    values.put("start_date", row.getObject("start_date", LocalDate.class));
                    values.put("end_date", row.getObject("end_date", LocalDate.class));
                    values.put("dose_times", row.getString("dose_times"));
                    values.put("dose_per_time", row.getInt("dose_per_time"));
                    values.put("timing", row.getString("timing"));
                    return values;
                }, myDrugId);
    }

    private String storedStatus() {
        return jdbc.queryForObject("SELECT status FROM chat_proposal WHERE proposal_id=?", String.class, proposalId);
    }

    private static void login(long memberId) {
        var user = new CustomUserDetails(memberId, "fixture@example.test", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private long memberRow() {
        return insert("INSERT INTO member(email,password_hash,nickname,user_role,created_at,updated_at) "
                + "VALUES(?,'synthetic','fixture','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID() + "@example.test");
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
