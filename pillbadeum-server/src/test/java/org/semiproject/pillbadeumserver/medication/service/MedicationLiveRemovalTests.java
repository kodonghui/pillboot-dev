package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationLiveRemovalTests {
    @Mock MedicationRepository reads;
    @Mock MedicationLivePlanner planner;
    @Mock MedicationScheduleService schedules;
    @InjectMocks MedicationLiveWorkflow workflow;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private final StartRequest selection = new StartRequest("offer", 13L, 25L, TODAY);
    private final MedicationTarget mom = target(27, 14, "엄마", "PARENT", "타이레놀정500밀리그램");
    private final MedicationTarget dad = target(28, 15, "아빠", "PARENT", "타이레놀정500밀리그램");

    @Test
    void findsMothersScheduleFromASpokenTypoAndDeletesOnlyThatSchedule() {
        given(plan("DELETE", "엄마", List.of("타일레놀"), "일정 삭제해줘"), mom, dad);
        var decision = prepare(user("엄마 타일레놀 먹던 거 일정 삭제해줘"));
        assertThat(decision.operation()).isEqualTo(Operation.DELETE);
        assertThat(decision.profileId()).isEqualTo(14);
        assertThat(decision.myDrugId()).isEqualTo(27); // 화면 선택값과 모델의 999번 ID는 사용하지 않는다.
        verifyNoInteractions(schedules);
        var outcome = workflow.execute(5, decision);
        verify(schedules).delete(5, 14, 27);
        verifyNoMoreInteractions(schedules);
        assertThat(outcome.savedSchedule()).isNull();
        assertThat(outcome.completedOperation()).isEqualTo(new CompletedOperation(Operation.DELETE, 14, 27));
        assertThat(outcome.message()).contains("삭제했어요");
    }

    @Test
    void stopUsesTheExistingServiceAndReturnsThePreservedHistoryEndingToday() {
        given(plan("STOP", "어머니", List.of("타이레놀"), "그만 먹을래"), mom, dad);
        var stopped = new MedicationTarget(27, 14, "엄마", "PARENT", mom.drugName(), 1, "식후 30분",
                mom.startDate(), TODAY, "07:00,11:00,17:00", mom.updatedAt());
        when(schedules.stop(5, 14, 27)).thenReturn(ScheduleResponse.from(stopped, false));
        var result = workflow.execute(5, prepare(user("어머니 타이레놀 그만 먹을래")));
        verify(schedules).stop(5, 14, 27);
        verifyNoMoreInteractions(schedules);
        assertThat(result.savedSchedule().startDate()).isEqualTo(mom.startDate());
        assertThat(result.savedSchedule().endDate()).isEqualTo(TODAY);
        assertThat(result.savedSchedule().doseTimes()).containsExactly("07:00", "11:00", "17:00");
        assertThat(result.completedOperation().action()).isEqualTo(Operation.STOP);
        assertThat(result.message()).contains("오늘까지만");
    }

    @Test
    void differentStrengthsAreAskedOnceInsteadOfTrustingTheModelsChosenId() {
        given(plan("DELETE", "엄마", List.of("타일레놀"), "삭제해줘"), mom,
                target(29, 14, "엄마", "PARENT", "타이레놀8시간이알서방정650밀리그램"));
        var result = prepare(user("엄마 타일레놀 삭제해줘"));
        assertThat(result.executable()).isFalse();
        assertThat(result.outcome().message()).contains("500", "650", "함량");
        verifyNoInteractions(schedules);
    }

    @Test
    void aStrengthReplyNarrowsTheOriginalDeletionRequest() {
        given(plan("DELETE", "엄마", List.of("타일레놀", "500"), "삭제해줘"), mom,
                target(29, 14, "엄마", "PARENT", "타이레놀8시간이알서방정650밀리그램"));
        var result = prepare(user("엄마 타일레놀 삭제해줘"), assistant("500과 650 중 어떤 약인가요?"), user("500짜리"));
        assertThat(result.executable()).isTrue();
        assertThat(result.myDrugId()).isEqualTo(27);
        assertThat(result.operation()).isEqualTo(Operation.DELETE);
    }

    @Test
    void anOmittedProfileDoesNotPickTheSelectedProfileWhenBothHaveTheSameDrug() {
        given(plan("DELETE", null, List.of("타이레놀"), "삭제해줘"), mom, dad);
        var result = prepare(user("타이레놀 일정 삭제해줘"));
        assertThat(result.executable()).isFalse();
        assertThat(result.outcome().message()).contains("엄마", "아빠", "누구");
        verifyNoInteractions(schedules);
    }

    @Test
    void unscheduledMedicationAndUnmatchedNamesDoNotFallbackToAnotherSchedule() {
        var unscheduled = new MedicationTarget(27, 14, "엄마", "PARENT", mom.drugName(), 1, null,
                null, null, null, mom.updatedAt());
        given(plan("DELETE", "엄마", List.of("타이레놀"), "삭제해줘"), unscheduled, dad);
        assertThat(prepare(user("엄마 타이레놀 삭제해줘")).executable()).isFalse();
        doReturn(plan("DELETE", "할머니", List.of("타이레놀"), "삭제해줘"))
                .when(planner).plan(any(), any(), anyList(), anyList(), anyList());
        assertThat(prepare(user("할머니 타이레놀 삭제해줘")).executable()).isFalse();
        verifyNoInteractions(schedules);
    }

    @Test
    void assistantOnlyEvidenceAndInventedUserHintsCannotAuthorizeDeletion() {
        given(plan("DELETE", "엄마", List.of("타이레놀"), "삭제해줘"), mom);
        assertThat(prepare(user("엄마 타이레놀"), assistant("삭제해줘라고 말씀하신 건가요?")).executable()).isFalse();
        assertThat(prepare(user("아빠 타이레놀 삭제해줘")).executable()).isFalse();
        verifyNoInteractions(schedules);
    }

    @Test
    void negationAndLaterCancellationBlockEvenAnIncorrectDeletePlan() {
        given(plan("DELETE", "엄마", List.of("타이레놀"), "삭제하지마"), mom);
        assertThat(prepare(user("엄마 타이레놀 삭제하지마")).executable()).isFalse();
        doReturn(plan("DELETE", "엄마", List.of("타이레놀"), "삭제해줘"))
                .when(planner).plan(any(), any(), anyList(), anyList(), anyList());
        assertThat(prepare(user("엄마 타이레놀 삭제해줘"), assistant("확인 중이에요"), user("취소해")).executable()).isFalse();
        assertThat(prepare(user("엄마 타이레놀 삭제해줘"), assistant("확인 중이에요"), user("아니요")).executable()).isFalse();
        assertThat(prepare(user("엄마 타이레놀 삭제해줘"), user("삭제 말고 오늘까지만")).executable()).isFalse();
        verifyNoInteractions(schedules);
    }

    @Test
    void notDeleteButStopTodayIsAStopOperation() {
        given(plan("STOP", "엄마", List.of("타이레놀"), "삭제하지 말고 오늘까지만"), mom);
        var decision = prepare(user("엄마 타이레놀 삭제하지 말고 오늘까지만"));
        assertThat(decision.operation()).isEqualTo(Operation.STOP);
        assertThat(decision.executable()).isTrue();
        verifyNoInteractions(schedules);
    }

    @Test
    void decimalStrengthIsNotConfusedWithTenTimesTheDose() {
        given(plan("DELETE", "엄마", List.of("테스트정", "2.5"), "삭제해줘"),
                target(31, 14, "엄마", "PARENT", "테스트정2.5밀리그램"),
                target(32, 14, "엄마", "PARENT", "테스트정25밀리그램"));
        assertThat(prepare(user("엄마 테스트정2.5 삭제해줘")).myDrugId()).isEqualTo(31);
    }

    @Test
    void explicitlySelectedScheduleCanBeUsedWithoutAName() {
        given(plan("DELETE", null, null, "삭제해줘"), mom,
                target(25, 13, "나", "SELF", "다른약"));
        assertThat(prepare(user("지금 선택한 일정 삭제해줘")).myDrugId()).isEqualTo(25);
    }

    @Test
    void failedDeletionNeverReturnsACompletedResult() {
        given(plan("DELETE", "엄마", List.of("타이레놀"), "삭제해줘"), mom);
        var decision = prepare(user("엄마 타이레놀 삭제해줘"));
        doThrow(new NotFoundException("일정이 이미 삭제됐어요")).when(schedules).delete(5, 14, 27);
        assertThatThrownBy(() -> workflow.execute(5, decision)).isInstanceOf(NotFoundException.class);
        verify(schedules).delete(5, 14, 27);
    }

    @SafeVarargs private final MedicationLiveWorkflow.Decision prepare(Map<String, Object>... parts) {
        return workflow.prepare(5, selection, TODAY, List.of(parts));
    }
    private void given(Plan plan, MedicationTarget... targets) {
        when(reads.allDrugs(5)).thenReturn(List.of(targets));
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(plan);
    }
    private Plan plan(String operation, String profile, List<String> drugs, String evidence) {
        return new Plan(operation, 999L, null, null, null, null, null, null, null,
                false, null, null, profile, drugs, evidence);
    }
    private static MedicationTarget target(long id, long profile, String name, String relation, String drug) {
        return new MedicationTarget(id, profile, name, relation, drug, 1, "식후 30분", TODAY.minusDays(3),
                TODAY.plusDays(10), "07:00,11:00,17:00", LocalDateTime.of(2026, 11, 3, 0, 0));
    }
    private Map<String, Object> user(String text) { return Map.of("role", "user", "content", text); }
    private Map<String, Object> assistant(String text) { return Map.of("role", "assistant", "content", text); }
}
