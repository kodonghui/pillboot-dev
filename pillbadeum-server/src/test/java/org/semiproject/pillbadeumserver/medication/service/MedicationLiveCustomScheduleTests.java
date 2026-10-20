package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.*;
import org.semiproject.pillbadeumserver.medication.dto.MedicationLiveDtos.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationLiveCustomScheduleTests {
    @Mock MedicationRepository reads;
    @Mock MedicationLivePlanner planner;
    @Mock MedicationScheduleService schedules;
    @InjectMocks MedicationLiveWorkflow workflow;
    private final LocalDate date = LocalDate.of(2026, 10, 20);
    private final StartRequest selection = new StartRequest("offer", 17L, null, date);

    @Test
    void profileWithoutAnyRegisteredDrugCanProposeThenConfirmACustomSchedule() {
        plan("혈압약", null, false);
        var proposal = workflow.prepare(12, selection, date, user("오늘부터 일주일 혈압약"));
        assertThat(proposal.executable()).isFalse();
        assertThat(proposal.proposal().customName()).isEqualTo("혈압약");
        assertThat(proposal.outcome().message()).contains("혈압약", "하루 3번", "1회 1개", "식후 30분", "적용할까요");
        verifyNoInteractions(schedules);

        var confirmed = workflow.confirm(proposal.proposal());
        when(schedules.createCustom(eq(12L), eq(17L), eq("혈압약"), any())).thenReturn(
                new ScheduleResponse(38, 17, "엄마", "혈압약", date, date.plusDays(6),
                        List.of("07:00", "11:00", "17:00"), 1, "식후 30분", false, true));
        var result = workflow.execute(12, confirmed);
        assertThat(result.completedOperation().myDrugId()).isEqualTo(38);
        assertThat(result.savedSchedule().drugName()).isEqualTo("혈압약");
        verify(schedules, never()).save(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void delegatedDefaultsCanCreateACustomScheduleWithoutAConfirmation() {
        plan("혈압약", null, true);
        var result = workflow.prepare(12, selection, date, user("혈압약 일주일 나머지는 알아서 저장해"));
        assertThat(result.executable()).isTrue();
        assertThat(result.customName()).isEqualTo("혈압약");
        assertThat(result.request().startDate()).isEqualTo(date);
        assertThat(result.request().dosePerTime()).isEqualTo(1);
        assertThat(result.request().timing()).isEqualTo("식후 30분");
        verifyNoInteractions(schedules);
    }

    @Test
    void explicitlyNamedCustomMedicationDoesNotOverwriteTheSelectedCatalogMedication() {
        plan("혈압약", null, true);
        var result = workflow.prepare(12, new StartRequest("offer", 17L, 34L, date), date,
                user("혈압약 일주일 나머지는 알아서 저장해"));
        assertThat(result.myDrugId()).isZero();
        assertThat(result.customName()).isEqualTo("혈압약");
    }

    @Test
    void inventedNamesOrForeignProfilesCannotReachSaving() {
        plan("당뇨약", null, true);
        assertThat(workflow.prepare(12, selection, date, user("혈압약 알아서 저장해")).executable()).isFalse();
        plan("혈압약", 999L, true);
        assertThat(workflow.prepare(12, selection, date, user("혈압약 알아서 저장해")).executable()).isFalse();
        verifyNoInteractions(schedules);
    }

    @Test
    void missingNameAsksForOneEvenWhenTheUserDelegatesDefaults() {
        plan(null, null, true);
        var result = workflow.prepare(12, selection, date, user("알아서 저장해"));
        assertThat(result.executable()).isFalse();
        assertThat(result.outcome().missingFields()).contains("target");
        assertThat(result.outcome().message()).contains("어떤 약");
        verifyNoInteractions(schedules);
    }

    private void plan(String name, Long profile, boolean defaults) {
        when(reads.profiles(12)).thenReturn(List.of(new ProfileChoice(17, "엄마", "MOTHER")));
        when(planner.plan(any(), any(), anyList(), anyList(), anyList())).thenReturn(
                new Plan("SAVE", null, null, null, 7, null, null, null, null, defaults,
                        defaults ? "알아서 저장해" : null, null, null, null, null, profile, name));
    }
    private List<Map<String, Object>> user(String text) { return List.of(Map.of("role", "user", "content", text)); }
}
