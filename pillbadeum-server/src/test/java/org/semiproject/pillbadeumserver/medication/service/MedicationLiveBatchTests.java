package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ProfileChoice;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleResponse;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationLiveBatchTests {
    @Mock MedicationRepository reads;
    @Mock MedicationLivePlanner planner;
    @Mock MedicationScheduleService schedules;
    @InjectMocks MedicationLiveWorkflow workflow;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private final StartRequest selection = new StartRequest("offer", 13L, 25L, DAY);

    @Test
    void twoDrugProposalPreservesEachDoseAndTimingAndConfirmSavesBothOnce() {
        ownedTwo();
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(batchPlan("두 개 다", "12:00"));
        var prepared = workflow.prepare(5, selection, DAY, List.of(user("두 개 다 일주일 12시에")));
        assertThat(prepared.executable()).isFalse();
        var proposal = prepared.proposal();
        assertThat(proposal.batch()).extracting(change -> change.request().dosePerTime()).containsExactly(1, 2);
        assertThat(proposal.batch()).extracting(change -> change.request().timing()).containsExactly("식전", "식후");
        assertThat(prepared.outcome().message()).contains("타이레놀정", "인데놀정", "12시", "1회 1개", "1회 2개", "적용할까요");
        verifyNoInteractions(schedules);
        when(schedules.saveBatch(eq(5L), eq(13L), anyList())).thenReturn(List.of(saved(25, "타이레놀정", 1, "식전"), saved(26, "인데놀정", 2, "식후")));
        var result = workflow.execute(5, workflow.confirm(proposal));
        assertThat(result.message()).contains("타이레놀정", "인데놀정", "함께 저장");
        assertThat(result.savedSchedule().myDrugId()).isEqualTo(25);
        assertThat(result.completedOperation()).isEqualTo(new CompletedOperation(Operation.SAVE, 13, 25));
        verify(schedules).saveBatch(5, 13, proposal.batch());
        verify(schedules, never()).save(anyLong(), anyLong(), anyLong(), any());
        verify(schedules, never()).createCustom(anyLong(), anyLong(), anyString(), any());
        verify(planner, times(1)).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void commonDateAndTimeCorrectionKeepsTheFrozenPairWithoutRepeatingBoth() {
        ownedTwo();
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(batchPlan("둘 다", "08:00"), batchPlan(null, "12:00"));
        var first = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 8시에")));
        var changed = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 8시에"), assistant("적용할까요?"),
                user("아니 수요일부터 12시에만")), first.proposal());
        assertThat(changed.proposal().batch()).extracting(change -> change.myDrugId()).containsExactly(25L, 26L);
        assertThat(changed.proposal().batch()).allSatisfy(change -> {
            assertThat(change.request().startDate()).isEqualTo(DAY);
            assertThat(change.request().endDate()).isEqualTo(DAY.plusDays(6));
            assertThat(change.request().doseTimes()).containsExactly(LocalTime.NOON);
        });
        verifyNoInteractions(schedules);
        verify(planner, times(2)).plan(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void cancellingTheBatchDiscardsTheProposalWithoutAnySave() {
        ownedTwo();
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(batchPlan("둘 다", "12:00"),
                new Plan("CANCEL", null, null, null, null, null, null, null, null, false, null));
        var first = workflow.prepare(5, selection, DAY, List.of(user("둘 다 12시에")));
        var cancelled = workflow.prepare(5, selection, DAY, List.of(user("둘 다 12시에"), assistant("적용할까요?"), user("등록 취소해")), first.proposal());
        assertThat(cancelled.proposal()).isNull();
        assertThat(cancelled.executable()).isFalse();
        assertThat(cancelled.outcome().message()).contains("취소");
        verifyNoInteractions(schedules);
    }

    @Test
    void explicitSingleDrugCorrectionShrinksTheBatchToThatDrug() {
        ownedTwo();
        var single = new Plan("SAVE", 26L, DAY.toString(), null, 7, List.of("12:00"), 1, null, null,
                false, null, null, null, List.of("인데놀정"), null);
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(batchPlan("둘 다", "12:00"), single);
        var first = workflow.prepare(5, selection, DAY, List.of(user("둘 다 12시에")));
        var changed = workflow.prepare(5, selection, DAY, List.of(user("둘 다 12시에"), assistant("적용할까요?"),
                user("둘 다 말고 인데놀정만 일주일")), first.proposal());
        assertThat(changed.proposal().batch()).isEmpty();
        assertThat(changed.proposal().myDrugId()).isEqualTo(26);
        assertThat(changed.proposal().request().dosePerTime()).isEqualTo(2);
        verifyNoInteractions(schedules);
    }

    @Test
    void anOtherProfileOrInventedBothEvidenceCannotCreateABatch() {
        ownedTwo();
        var other = new Plan("SAVE", null, DAY.toString(), null, 7, List.of("12:00"), 1, null, null,
                false, null, null, "아빠", null, "둘 다", 14L, null);
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(other, batchPlan("전부", "12:00"));
        var result = workflow.prepare(5, selection, DAY, List.of(user("아빠 약 둘 다 일주일")));
        assertThat(result.proposal()).isNull();
        assertThat(result.outcome().missingFields()).contains("target");
        var invented = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일")));
        assertThat(invented.proposal()).isNull();
        verifyNoInteractions(schedules);
    }

    @Test
    void aChangedPairCannotSilentlyReplaceTheSecondDrugInTheStoredProposal() {
        ownedTwo();
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(batchPlan("둘 다", "12:00"), batchPlan(null, "08:00"));
        var first = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일")));
        when(reads.allDrugs(5)).thenReturn(List.of(target(25, "타이레놀정", 1, "식전"), target(27, "새약", 1, null)));
        var result = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일"), assistant("적용할까요?"), user("8시로")), first.proposal());
        assertThat(result.proposal()).isNull();
        assertThat(result.outcome().message()).contains("약 목록이 바뀌");
        verifyNoInteractions(schedules);
    }

    @Test
    void equalProfileIdsAboveTheBoxedLongCacheStillCreateABatch() {
        var selected = new StartRequest("offer", 1000L, 25L, DAY);
        when(reads.allDrugs(5)).thenReturn(List.of(
                new MedicationTarget(25, 1000, "본인", "SELF", "타이레놀정", 1, "식전", null, null, null, null),
                new MedicationTarget(26, 1000, "본인", "SELF", "인데놀정", 2, "식후", null, null, null, null)));
        var plan = new Plan("SAVE", null, DAY.toString(), null, 7, List.of("12:00"), 1, null, null,
                false, null, null, null, null, "두 개 다", Long.valueOf(1000), null);
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(plan);
        var result = workflow.prepare(5, selected, DAY, List.of(user("두 개 다 일주일 12시에")));
        assertThat(result.proposal().profileId()).isEqualTo(1000);
        assertThat(result.proposal().batch()).hasSize(2);
    }

    @Test
    void aPartialTimeCorrectionKeepsTheProposedDatesDoseAndTiming() {
        ownedTwo();
        var firstPlan = new Plan("SAVE", null, DAY.toString(), null, 7, List.of("08:00"), 1, 3, "식후 30분",
                false, null, null, null, null, "둘 다");
        var partial = new Plan("SAVE", null, null, null, null, List.of("12:00"), 1, null, null, false, null);
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(firstPlan, partial);
        var first = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 8시 세 개씩 식후 30분")));
        var changed = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 8시 세 개씩 식후 30분"),
                assistant("적용할까요?"), user("12시에만")), first.proposal());
        assertThat(changed.proposal().batch()).hasSize(2).allSatisfy(change -> {
            assertThat(change.request().startDate()).isEqualTo(DAY);
            assertThat(change.request().endDate()).isEqualTo(DAY.plusDays(6));
            assertThat(change.request().doseTimes()).containsExactly(LocalTime.NOON);
            assertThat(change.request().dosePerTime()).isEqualTo(3);
            assertThat(change.request().timing()).isEqualTo("식후 30분");
        });
    }

    @Test
    void aPartialStartDateCorrectionKeepsTheDurationAndProposedTime() {
        ownedTwo();
        var partial = new Plan("SAVE", null, DAY.plusDays(7).toString(), null, null, null, null, null, null, false, null);
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(batchPlan("둘 다", "08:00"), partial);
        var first = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 8시에")));
        var changed = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 8시에"), assistant("적용할까요?"),
                user("다음주 수요일부터")), first.proposal());
        assertThat(changed.proposal().batch()).allSatisfy(change -> {
            assertThat(change.request().startDate()).isEqualTo(DAY.plusDays(7));
            assertThat(change.request().endDate()).isEqualTo(DAY.plusDays(13));
            assertThat(change.request().doseTimes()).containsExactly(LocalTime.of(8, 0));
        });
    }

    @Test
    void narrowingWithoutNamingADrugCannotFallBackToTheSelectedOne() {
        ownedTwo();
        var guessed = new Plan("SAVE", 25L, DAY.toString(), null, 7, List.of("12:00"), 1, null, null, false, null);
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(batchPlan("둘 다", "12:00"), guessed);
        var first = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 12시에")));
        var changed = workflow.prepare(5, selection, DAY, List.of(user("둘 다 일주일 12시에"), assistant("적용할까요?"),
                user("둘 다 말고 하나만")), first.proposal());
        assertThat(changed.proposal()).isNull();
        assertThat(changed.executable()).isFalse();
        assertThat(changed.outcome().missingFields()).contains("target");
        verifyNoInteractions(schedules);
    }

    @Test
    void aNormalSingleCustomDrugRequestDoesNotNeedAnExistingBatchTarget() {
        when(reads.profiles(5)).thenReturn(List.of(new ProfileChoice(13, "본인", "SELF")));
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(
                new Plan("SAVE", null, null, null, 7, null, null, null, null, false,
                        null, null, null, null, null, 13L, "혈압약"));
        var result = workflow.prepare(5, selection, DAY, List.of(user("혈압약 하나만 일주일 등록해줘")));
        assertThat(result.proposal()).isNotNull();
        assertThat(result.proposal().customName()).isEqualTo("혈압약");
        assertThat(result.proposal().batch()).isEmpty();
        verifyNoInteractions(schedules);
    }

    private void ownedTwo() {
        when(reads.allDrugs(5)).thenReturn(List.of(target(26, "인데놀정", 2, "식후"), target(25, "타이레놀정", 1, "식전")));
    }
    private MedicationTarget target(long id, String name, int dose, String timing) {
        return new MedicationTarget(id, 13, "본인", "SELF", name, dose, timing, null, null, null, null);
    }
    private Plan batchPlan(String evidence, String time) {
        return new Plan("SAVE", null, DAY.toString(), null, 7, List.of(time), 1, null, null,
                false, null, null, null, null, evidence);
    }
    private ScheduleResponse saved(long id, String name, int dose, String timing) {
        return new ScheduleResponse(id, 13, "본인", name, DAY, DAY.plusDays(6), List.of("12:00"), dose, timing, false, true);
    }
    private Map<String, Object> user(String text) { return Map.of("role", "user", "content", text); }
    private Map<String, Object> assistant(String text) { return Map.of("role", "assistant", "content", text); }
}
