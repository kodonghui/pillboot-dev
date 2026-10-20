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
    void missingInformationIsProposedTogetherWithoutApplyingDefaultsOrSaving() {
        plan(new Plan("SAVE", null, null, null, null, null, null, null, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("약 일정 추가해줘")));
        assertThat(result.request()).isNull();
        assertThat(result.outcome().missingFields()).containsExactly("endDate", "doseTimes", "dosePerTime");
        assertThat(result.outcome().message()).contains("오늘부터 1일", "하루 3번", "7·11·17시", "1회 2개", "적용할까요");
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
        assertThat(result.proposal().request().doseTimes()).containsExactly(LocalTime.of(7, 0), LocalTime.of(11, 0), LocalTime.of(17, 0));
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
        assertThat(result.request().doseTimes()).containsExactly(LocalTime.of(7, 0), LocalTime.of(11, 0), LocalTime.of(17, 0));
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
        assertThat(result.outcome().message()).contains("하루 1번", "17시").doesNotContain("7·11·17시");
        verifyNoInteractions(schedules);
    }

    @Test
    void twoExplicitDailyDosesProposeTwoTimesAndKeepTheExplicitQuantity() {
        plan(new Plan("SAVE", null, "2026-11-05", null, 2, null, 2, 3, null, false, null));
        var result = workflow.prepare(5, selection, DATE, List.of(user("5일부터 이틀 하루 두 번 세 개씩")));
        assertThat(result.proposal().request().doseTimes()).containsExactly(LocalTime.of(7, 0), LocalTime.of(11, 0));
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

    private void plan(Plan plan) {
        when(reads.allDrugs(5)).thenReturn(List.of(new MedicationTarget(25, 13, "엄마", "MOTHER", "테스트약", 2, "식후",
                null, null, null, LocalDateTime.of(2026, 10, 19, 9, 0))));
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(plan);
    }
    private Map<String, Object> user(String text) { return Map.of("role", "user", "content", text); }
    private Map<String, Object> assistant(String text) { return Map.of("role", "assistant", "content", text); }
}
