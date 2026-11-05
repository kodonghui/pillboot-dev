package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationLiveWorkflowTests {
    @Mock MedicationRepository reads;
    @Mock MedicationLivePlanner planner;
    @Mock MedicationScheduleService schedules;
    @InjectMocks MedicationLiveWorkflow workflow;
    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private final StartRequest selection = new StartRequest("offer", 13L, 25L, DATE);

    @Test
    void medicationListQuestionReturnsOnlyTheSelectedProfilesDrugsWithoutPlanning() {
        when(reads.allDrugs(5)).thenReturn(List.of(target(25, 13, "타이레놀정"), target(26, 13, "인데놀정"),
                target(27, 14, "다른프로필약")));
        var result = workflow.prepare(5, selection, DATE, List.of(user("나 먹는 약 뭐 있어")));
        assertThat(result.executable()).isFalse();
        assertThat(result.outcome().message()).contains("타이레놀정", "인데놀정").doesNotContain("다른프로필약");
        verifyNoInteractions(planner, schedules);
        verify(reads, never()).profiles(anyLong());
    }

    @Test
    void medicationListReportsNoRegisteredDrugsForTheSelectedProfile() {
        when(reads.allDrugs(5)).thenReturn(List.of(target(27, 14, "다른프로필약")));
        var result = workflow.prepare(5, selection, DATE, List.of(user("약 목록 알려줘")));
        assertThat(result.outcome().message()).isEqualTo("선택한 프로필에 등록된 약이 없어요.");
        assertThat(result.executable()).isFalse();
        verifyNoInteractions(planner, schedules);
    }

    @Test
    void listQueryUsesTheLatestUserTurnAndJoinsItsTranscriptFragments() {
        when(reads.allDrugs(5)).thenReturn(List.of(target(25, 13, "타이레놀정")));
        var result = workflow.prepare(5, selection, DATE, List.of(user("약 둘 다 등록해줘"),
                assistant("어떤 약부터 등록할까요?"), user("나 먹는 약 "), user("뭐 있어")));
        assertThat(result.outcome().message()).contains("타이레놀정").doesNotContain("한 약씩");
        verifyNoInteractions(planner, schedules);
    }

    @Test
    void timedListQuestionSurvivesAssistantSpeechBetweenEveryUserFragment() {
        when(reads.allDrugs(5)).thenReturn(List.of(target(25, 13, "타이레놀정"), target(26, 13, "인데놀정")));
        var result = workflow.prepare(5, selection, DATE, List.of(
                timed("user", "나", 7200, 7400), timed("assistant", "입니다", 7300, 7500),
                timed("user", " 먹는", 7600, 7800), timed("assistant", ".", 7700, 7900),
                timed("user", " 약", 8000, 8200), timed("assistant", "어떻게", 8100, 8300),
                timed("user", " 뭐", 8200, 8400), timed("assistant", " 일정", 8300, 8700),
                timed("user", " 있어", 8600, 8800), timed("assistant", " 등록을 도와드릴까요?", 8800, 9100)));
        assertThat(result.outcome().message()).contains("타이레놀정", "인데놀정").doesNotContain("약 이름과 기간");
        assertThat(result.executable()).isFalse();
        verifyNoInteractions(planner, schedules);
    }

    @Test
    void aPreviousBulkRequestIsSeparatedFromTheNextSingleDrugUtteranceByItsTimeGap() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(
                timed("user", "약 둘 다 등록해줘", 1000, 2000),
                timed("assistant", "어떤 약부터 등록할까요?", 2500, 3500),
                timed("user", "테스트약만", 8000, 8700), timed("assistant", "네", 8500, 9000),
                timed("user", " 다음주 수요일부터 일주일", 8700, 9800)));
        assertThat(result.proposal()).isNotNull();
        assertThat(result.outcome().message()).doesNotContain("한 약씩");
        verify(planner).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void aTimeGapAlsoSeparatesConsecutiveUserFragmentsWithoutAnAssistantCaption() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(timed("user", "약 둘 다", 1000, 2000),
                timed("user", "테스트약만 다음주부터 일주일", 8000, 9000)));
        assertThat(result.proposal()).isNotNull();
        verify(planner).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void missingOrRepeatedFixtureTimestampsKeepTheExistingRoleBoundary() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        for (var transcript : List.of(
                List.of(user("약 둘 다"), assistant("어떤 약부터?"), user("테스트약만 등록해줘")),
                List.of(timed("user", "약 둘 다", 0, 1000), timed("assistant", "어떤 약부터?", 0, 1000),
                        timed("user", "테스트약만 등록해줘", 0, 1000)))) {
            var result = workflow.prepare(5, selection, DATE, transcript);
            assertThat(result.proposal()).isNotNull();
            assertThat(result.outcome().message()).doesNotContain("한 약씩");
        }
        verify(planner, times(2)).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void joiningAClinicalQuestionDoesNotTurnItIntoAnInventoryQuery() {
        plan(new Plan("ASK", null, null, null, null, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(timed("user", "어떤 약", 1000, 1500),
                timed("assistant", "네", 1200, 1700), timed("user", " 먹어야 해", 1500, 2100)));
        assertThat(result.outcome().message()).doesNotContain("테스트약");
        verify(planner).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void anInterleavedRetractionCannotReuseAnEarlierDefaultsDelegation() {
        plan(new Plan("SAVE", null, null, null, 7, List.of("08:00"), 1, null, null, true, "알아서 저장해"));
        var result = workflow.prepare(5, selection, DATE, List.of(timed("user", "알아서 저장해", 1000, 2000),
                timed("assistant", "네", 2500, 3000), timed("user", "기본값", 5000, 5300),
                timed("assistant", "확인할게요", 5100, 5600), timed("user", " 말고 일주일 8시", 5300, 6500)));
        assertThat(result.executable()).isFalse();
        assertThat(result.outcome().missingFields()).contains("correction");
        verifyNoInteractions(schedules);
    }

    @Test
    void aScheduleCommandWithAQuestionWordStillUsesThePlanner() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        workflow.prepare(5, selection, DATE, List.of(user("나 먹는 약 뭐 있어? 이 약을 등록해줘")));
        verify(planner).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void explicitBulkRequestsWithoutSelectedProfileDrugsAskWithoutPlanningOrSaving() {
        when(reads.allDrugs(5)).thenReturn(List.of(target(27, 14, "다른프로필약")));
        for (String text : List.of("약 둘 다 다음주 수요일부터 일주일", "두 약 모두 등록해줘",
                "전부 다음주 수요일부터 일주일", "모든 약 등록해줘")) {
            var result = workflow.prepare(5, selection, DATE, List.of(user(text)));
            assertThat(result.executable()).isFalse();
            assertThat(result.proposal()).isNull();
            assertThat(result.outcome().missingFields()).containsExactly("target");
            assertThat(result.outcome().message()).contains("등록된 약이 없어요")
                    .doesNotContain("다른프로필약");
        }
        verifyNoInteractions(planner, schedules);
    }

    @Test
    void negatedBulkPhrasesAllowTheCorrectedSingleDrugRequest() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        for (String text : List.of("둘 다 말고 테스트약만 다음주부터 일주일", "전부가 아니라 테스트약만 등록해줘")) {
            var result = workflow.prepare(5, selection, DATE, List.of(user(text)));
            assertThat(result.proposal()).isNotNull();
            assertThat(result.outcome().message()).doesNotContain("한 약씩");
        }
        verify(planner, times(2)).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void morningStartDoesNotBecomeAnAllDrugsRequest() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("내일 오전부터 이 약 등록해줘")));
        assertThat(result.proposal()).isNotNull();
        verify(planner).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void cancellingABulkRequestStillUsesTheExistingCancellationPath() {
        plan(new Plan("CANCEL", null, null, null, null, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("약 둘 다 등록하지 마 취소해")));
        assertThat(result.outcome().message()).contains("이번 요청은 취소");
        assertThat(result.executable()).isFalse();
        verify(planner).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void missingInformationIsProposedTogetherWithoutApplyingDefaultsOrSaving() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("약 일정 추가해줘")));
        assertThat(result.request()).isNull();
        assertThat(result.outcome().missingFields()).containsExactly("endDate", "doseTimes", "dosePerTime");
        assertThat(result.outcome().message()).contains("오늘부터 1일", "하루 3번", "7·12·17시", "1회 2개", "적용할까요");
        assertThat(result.outcome().message()).doesNotContain("어떻게", "몇 시", "며칠");
        assertThat(result.outcome().appliedDefaults()).isEmpty();
        assertThat(result.proposal().request().endDate()).isEqualTo(DATE);
        verifyNoInteractions(schedules);
    }

    @Test
    void dailyCountAloneGetsSpecificDefaultTimesAsOneProposal() {
        plan(new Plan("SAVE", null, null, null, 7, null, 3, 1, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("일주일 동안 세 번 한 개씩")));
        assertThat(result.request()).isNull();
        assertThat(result.outcome().missingFields()).containsExactly("doseTimes");
        assertThat(result.outcome().message()).contains("하루 3번", "적용할까요");
        assertThat(result.proposal().request().doseTimes()).containsExactly(LocalTime.of(7, 0), LocalTime.of(12, 0), LocalTime.of(17, 0));
        verifyNoInteractions(schedules);
    }

    @Test
    void explicitDelegationFillsOnlyMissingFieldsAndKeepsAlreadyGivenValues() {
        plan(new Plan("SAVE", null, "2026-11-05", null, 7, List.of("08:00", "19:00"), 2, null, "식전", true, "알아서 저장해"));
        var result = workflow.prepare(5, selection, DATE, List.of(user("5일부터 일주일, 8시와 19시 식전으로 하고 나머지는 알아서 저장해")));
        assertThat(result.request().startDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(result.request().endDate()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(result.request().doseTimes()).containsExactly(LocalTime.of(8, 0), LocalTime.of(19, 0));
        assertThat(result.request().dosePerTime()).isEqualTo(2);
        assertThat(result.request().timing()).isEqualTo("식전");
        assertThat(result.outcome().appliedDefaults()).containsExactly("dosePerTime");
        verifyNoInteractions(schedules); // prepare는 완성된 초안도 저장하지 않는다.
    }

    @Test
    void allMissingFieldsUseSelectedDayAndExistingDoseOnlyAfterDelegation() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, true, "아몰라 알아서 저장해"));
        var result = workflow.prepare(5, selection, DATE, List.of(user("아몰라 알아서 저장해")));
        assertThat(result.request().startDate()).isEqualTo(DATE);
        assertThat(result.request().endDate()).isEqualTo(DATE);
        assertThat(result.request().doseTimes()).containsExactly(LocalTime.of(7, 0), LocalTime.of(12, 0), LocalTime.of(17, 0));
        assertThat(result.request().dosePerTime()).isEqualTo(2);
        assertThat(result.outcome().appliedDefaults()).contains("endDate", "doseTimes", "dosePerTime");
    }

    @Test
    void merelySayingDontKnowDoesNotAuthorizeDefaults() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, true, "몰라"));
        var result = workflow.prepare(5, selection, DATE, List.of(user("몰라")));
        assertThat(result.request()).isNull();
        assertThat(result.outcome().message()).contains("기본", "적용할까요");
    }

    @Test
    void assistantSuggestionOrInventedEvidenceDoesNotAuthorizeDefaults() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, true, "알아서 저장해"));
        var result = workflow.prepare(5, selection, DATE, List.of(user("약 일정 추가해줘"), assistant("알아서 저장해 달라는 뜻인가요?")));
        assertThat(result.request()).isNull();
        verifyNoInteractions(schedules);
    }

    @Test
    void yesCannotAuthorizeDefaultsBasedOnlyOnAssistantTextOrAModelFlag() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, true, "네"));
        assertThat(workflow.prepare(5, selection, DATE,
                List.of(assistant("기본값으로 저장할까요?"), user("네"))).request()).isNull();
        assertThat(workflow.prepare(5, selection, DATE,
                List.of(assistant("기본값으로 저장할까요?"), user("네"), assistant("확인할게요"))).request()).isNull();
        assertThat(workflow.prepare(5, selection, DATE,
                List.of(assistant("약 이름이 맞나요?"), user("네"))).request()).isNull();
    }

    @Test
    void latestRevocationBlocksEarlierDefaultDelegation() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, true, "알아서 저장해"));
        var result = workflow.prepare(5, selection, DATE,
                List.of(user("알아서 저장해"), assistant("확인할게요"), user("아니 기본값 쓰지마")));
        assertThat(result.request()).isNull();
    }

    @Test
    void completeInformationNeedsNoDefaultPermissionAndCancellationDoesNotSave() {
        plan(new Plan("SAVE", null, null, null, 7, List.of("08:00"), 1, 1, null, false, null));
        assertThat(workflow.prepare(5, selection, DATE, List.of(user("일주일 동안 8시에 한 개씩 등록"))).request()).isNotNull();
        doReturn(new Plan("CANCEL", null, null, null, null, null, null, null, null, false, null))
                .when(planner).plan(any(), any(), anyList(), anyList(), anyList());
        assertThat(workflow.prepare(5, selection, DATE, List.of(user("취소해"))).request()).isNull();
        verifyNoInteractions(schedules);
    }

    @Test
    void invalidValuesAreNotReplacedWithDefaults() {
        plan(new Plan("SAVE", null, null, null, 7, List.of("25:00"), 1, 1, null, true, "알아서 저장해"));
        assertThatThrownBy(() -> workflow.prepare(5, selection, DATE, List.of(user("25시에, 나머지는 알아서 저장해"))))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(schedules);
    }

    @Test
    void shortDrugAndDurationRequestProposesTheCompleteScheduleOnce() {
        plan(new Plan("SAVE", 25L, DATE.toString(), null, 2, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("오늘부터 이틀 테스트약")));
        assertThat(result.request()).isNull();
        assertThat(result.outcome().message()).contains("오늘부터 2일 동안 테스트약", "기본 하루 3번", "1회 2개", "식후", "적용할까요");
        var approved = workflow.confirm(result.proposal());
        assertThat(approved.request().endDate()).isEqualTo(DATE.plusDays(1));
        assertThat(approved.request().dosePerTime()).isEqualTo(2);
        assertThat(approved.outcome().appliedDefaults()).contains("doseTimes", "dosePerTime", "timing");
        verifyNoInteractions(schedules);
    }

    @Test
    void eveningOnlyProposesTheEveningSlotInsteadOfTheFirstDefaultTime() {
        plan(new Plan("SAVE", 25L, DATE.toString(), null, 2, null, 1, null, null, false, null, List.of("EVENING")));
        var result = workflow.prepare(5, selection, DATE, List.of(user("오늘부터 이틀 테스트약 저녁에만")));
        assertThat(result.request()).isNull();
        assertThat(result.proposal().request().doseTimes()).containsExactly(LocalTime.of(17, 0));
        assertThat(result.outcome().message()).contains("하루 1번", "17시").doesNotContain("7·12·17시");
        verifyNoInteractions(schedules);
    }

    @Test
    void twoExplicitDailyDosesProposeTwoTimesAndKeepTheExplicitQuantity() {
        plan(new Plan("SAVE", null, "2026-11-05", null, 2, null, 2, 3, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("5일부터 이틀 하루 두 번 세 개씩")));
        assertThat(result.proposal().request().doseTimes()).containsExactly(LocalTime.of(7, 0), LocalTime.of(12, 0));
        assertThat(result.proposal().request().dosePerTime()).isEqualTo(3);
        assertThat(result.proposal().request().startDate()).isEqualTo(DATE.plusDays(2));
        assertThat(result.proposal().defaults()).doesNotContain("dosePerTime", "endDate");
    }

    @Test
    void duplicatedPeriodsAndContradictoryCountsAreNotProposed() {
        plan(new Plan("SAVE", null, null, null, 2, null, 2, null, null, false, null, List.of("EVENING", "EVENING")));
        assertThatThrownBy(() -> workflow.prepare(5, selection, DATE, List.of(user("저녁에만"))))
                .isInstanceOf(BusinessException.class);
        doReturn(new Plan("SAVE", null, null, null, 2, null, 3, null, null, false, null, List.of("EVENING")))
                .when(planner).plan(any(), any(), anyList(), anyList(), anyList());
        assertThatThrownBy(() -> workflow.prepare(5, selection, DATE, List.of(user("저녁에만"))))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(schedules);
    }

    @Test
    void refusalDoesNotRepeatTheSameProposalOrSave() {
        plan(new Plan("ASK", null, null, null, 2, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("오늘부터 이틀"), assistant("기본 설정으로 저장할까요?"), user("아니요")));
        assertThat(result.request()).isNull();
        assertThat(result.proposal()).isNull();
        assertThat(result.outcome().message()).contains("바꿀 항목").doesNotContain("어떻게", "저장할까요");
        verifyNoInteractions(schedules);
    }

    @Test
    void explicitValuesAfterRejectingDefaultsStillProduceTheRequestedSchedule() {
        plan(new Plan("SAVE", null, null, null, 7, List.of("08:00"), 1, 1, null, false, null));
        var result = workflow.prepare(5, selection, DATE,
                List.of(user("기본값 말고 일주일 동안 오전 8시에 한 개씩")));
        assertThat(result.request().doseTimes()).containsExactly(LocalTime.of(8, 0));
        assertThat(result.request().endDate()).isEqualTo(DATE.plusDays(6));
        assertThat(result.outcome().appliedDefaults()).isEmpty();
        assertThat(result.proposal()).isNull();
        verifyNoInteractions(schedules);
    }

    @Test
    void compactProposalKeepsMinutesQuantityAndTimingWithoutRepeatingAmPm() {
        plan(new Plan("SAVE", null, null, null, 2, List.of("07:30", "17:15"), 2, null, "식후 30분", false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("이틀 동안 7시 반과 17시 15분 식후 30분")));
        assertThat(result.outcome().message()).contains("7시 30분·17시 15분", "1회 2개", "식후 30분", "적용할까요");
        assertThat(result.outcome().message()).doesNotContain("오전", "오후", "기존 설정인", "복용 때는");
        assertThat(result.proposal().request().doseTimes()).containsExactly(LocalTime.of(7, 30), LocalTime.of(17, 15));
    }

    @Test
    void savingOverAnExistingScheduleAsksFirstAndSaysTheCurrentOneGoesAway() {
        when(reads.allDrugs(5)).thenReturn(List.of(new MedicationTarget(25, 13, "엄마", "MOTHER", "테스트약", 2, "식후",
                DATE.minusDays(3), DATE.plusDays(4), "07:30,20:00", LocalDateTime.of(2026, 10, 19, 9, 0))));
        when(planner.plan(any(), any(), anyList(), anyList(), anyList()))
                .thenReturn(new Plan("SAVE", null, null, null, 7, List.of("08:00"), 1, 1, "식후", false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("일주일 동안 아침 8시에 식후 한 알")));
        assertThat(result.proposal()).isNotNull();
        assertThat(result.request()).isNull();
        assertThat(result.outcome().message()).contains("10월 31일부터 11월 7일까지", "7시 30분·20시", "지금 일정은 사라지고", "적용할까요");
        verifyNoInteractions(schedules);
    }

    private void plan(Plan plan) {
        when(reads.allDrugs(5)).thenReturn(List.of(new MedicationTarget(25, 13, "엄마", "MOTHER", "테스트약", 2, "식후",
                null, null, null, LocalDateTime.of(2026, 10, 19, 9, 0))));
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(plan);
    }
    private MedicationTarget target(long id, long profileId, String name) {
        return new MedicationTarget(id, profileId, "합성프로필", "SELF", name, 1, null, null, null, null, null);
    }
    private Map<String, Object> user(String text) { return Map.of("role", "user", "content", text); }
    private Map<String, Object> assistant(String text) { return Map.of("role", "assistant", "content", text); }
    private Map<String, Object> timed(String role, String text, long start, long end) {
        return Map.of("role", role, "content", text, "start_ms", start, "end_ms", end);
    }
}
