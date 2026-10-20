package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationScheduleWriteRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationScheduleServiceTests {
    @Mock MedicationRepository reads;
    @Mock MedicationScheduleWriteRepository writes;
    MedicationScheduleService service;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @BeforeEach
    void setUp() {
        // UTC 18일이어도 한국에서는 19일이다.
        service = new MedicationScheduleService(reads, writes,
                Clock.fixed(Instant.parse("2026-10-18T15:30:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void replacesScheduleUsingExistingEntityAndReturnsNewValues() {
        MyDrug drug = writable(TODAY.minusDays(1), TODAY.plusDays(20));
        var result = service.save(7, 2, 3, new ScheduleRequest(TODAY, TODAY.plusDays(6),
                List.of(LocalTime.of(19, 0), LocalTime.of(8, 0)), 2, null));

        assertThat(result.myDrugId()).isEqualTo(3);
        assertThat(result.endDate()).isEqualTo(TODAY.plusDays(6));
        assertThat(result.doseTimes()).containsExactly("08:00", "19:00");
        assertThat(result.dosePerTime()).isEqualTo(2);
        assertThat(drug.getTiming()).isNull();
        verify(writes).findForUpdate(2, 3);
    }

    @Test
    void customScheduleRequiresOwnershipAndReturnsTheGeneratedMedicationId() {
        when(reads.profiles(7)).thenReturn(List.of(new org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ProfileChoice(2, "엄마", "MOTHER")));
        when(writes.save(any())).thenAnswer(call -> {
            MyDrug row = call.getArgument(0);
            assertThat(row.getDrug()).isNull();
            assertThat(row.getCustomName()).isEqualTo("혈압약");
            assertThat(row.getSource()).isEqualTo(DrugSource.MANUAL);
            ReflectionTestUtils.setField(row, "myDrugId", 38L);
            return row;
        });
        var saved = service.createCustom(7, 2, " 혈압약 ", request(1));
        assertThat(saved.myDrugId()).isEqualTo(38);
        assertThat(saved.drugName()).isEqualTo("혈압약");
        assertThat(saved.profileName()).isEqualTo("엄마");
        assertThat(saved.hasSchedule()).isTrue();
        assertThatThrownBy(() -> service.createCustom(99, 2, "혈압약", request(1)))
                .isInstanceOf(NotFoundException.class);
        verify(writes, times(1)).save(any());
    }

    @Test
    void invalidCustomNameOrScheduleDoesNotCreateAnUnscheduledRow() {
        when(reads.profiles(7)).thenReturn(List.of(new org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ProfileChoice(2, "엄마", "MOTHER")));
        for (String name : new String[]{null, " ", "약".repeat(101)}) {
            assertThatThrownBy(() -> service.createCustom(7, 2, name, request(1))).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.createCustom(7, 2, "혈압약", request(11))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void missingOrForeignTargetCannotReachWriteRepository() {
        when(reads.find(99, 2, 3)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(99, 2, 3, request(1))).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void invalidCountAndDuplicateTimesLeaveEntityUnchanged() {
        MyDrug drug = writable(TODAY.minusDays(1), TODAY.plusDays(20));
        assertThatThrownBy(() -> service.save(7, 2, 3, request(11))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.save(7, 2, 3, new ScheduleRequest(TODAY, TODAY.plusDays(6),
                List.of(LocalTime.of(8, 0), LocalTime.of(8, 0)), 1, null))).isInstanceOf(BusinessException.class);
        assertThat(drug.getDosePerTime()).isEqualTo(1);
        assertThat(drug.getEndDate()).isEqualTo(TODAY.plusDays(20));
        assertThat(drug.getTiming()).isEqualTo("식전");
    }

    @Test
    void stopUsesKoreanTodayAndCanBeRepeatedOnTheSameDay() {
        MyDrug drug = writable(TODAY.minusDays(1), TODAY.plusDays(20));
        assertThat(service.stop(7, 2, 3).endDate()).isEqualTo(TODAY);
        assertThat(service.stop(7, 2, 3).endDate()).isEqualTo(TODAY);
        assertThat(drug.getStartDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(drug.getDoseTimes()).containsExactly(LocalTime.of(7, 0));
        assertThat(drug.getTiming()).isEqualTo("식전");
    }

    @Test
    void futureScheduleCannotBeStoppedByCreatingAnInvalidDateRange() {
        MyDrug drug = writable(TODAY.plusDays(1), TODAY.plusDays(20));
        assertThatThrownBy(() -> service.stop(7, 2, 3)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE));
        assertThat(drug.getEndDate()).isEqualTo(TODAY.plusDays(20));
    }

    @Test
    void endedScheduleCannotBeExtendedByStop() {
        MyDrug drug = writable(TODAY.minusDays(7), TODAY.minusDays(1));
        assertThatThrownBy(() -> service.stop(7, 2, 3)).isInstanceOf(BusinessException.class);
        assertThat(drug.getEndDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void deletingSchedulePreservesTheMedicationAndItsOtherInformation() {
        MyDrug drug = writable(TODAY, TODAY.plusDays(6));
        Drug catalogDrug = drug.getDrug();
        service.delete(7, 2, 3);

        assertThat(drug.hasSchedule()).isFalse();
        assertThat(drug.getStartDate()).isNull();
        assertThat(drug.getEndDate()).isNull();
        assertThat(ReflectionTestUtils.getField(drug, "doseTimes")).isNull();
        assertThat(drug.getMyDrugId()).isEqualTo(3);
        assertThat(drug.getProfileId()).isEqualTo(2);
        assertThat(drug.getDrug()).isSameAs(catalogDrug);
        assertThat(drug.getSource()).isEqualTo(DrugSource.SEARCH);
        assertThat(drug.getDosePerTime()).isEqualTo(1);
        assertThat(drug.getTiming()).isEqualTo("식전");
        verify(writes).findForUpdate(2, 3);
        verifyNoMoreInteractions(writes);
    }

    @Test
    void deletedScheduleCanBeRegisteredAgainOnTheSameMedication() {
        MyDrug drug = writable(TODAY, TODAY.plusDays(6));
        service.delete(7, 2, 3);
        readable(null, null);

        var result = service.save(7, 2, 3, request(2));

        assertThat(drug.hasSchedule()).isTrue();
        assertThat(result.hasSchedule()).isTrue();
        assertThat(result.myDrugId()).isEqualTo(3);
        assertThat(result.startDate()).isEqualTo(TODAY);
        assertThat(result.dosePerTime()).isEqualTo(2);
    }

    @Test
    void getWithoutScheduleReturnsNotFoundWhileMedicationStillExists() {
        readable(null, null);
        assertThatThrownBy(() -> service.get(7, 2, 3)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void deletingTwiceOrStoppingAfterDeleteReturnsNotFound() {
        MyDrug drug = writable(TODAY, TODAY.plusDays(6));
        service.delete(7, 2, 3);
        assertThatThrownBy(() -> service.delete(7, 2, 3)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.stop(7, 2, 3)).isInstanceOf(NotFoundException.class);
        assertThat(drug.hasSchedule()).isFalse();
    }

    @Test
    void foreignMemberCannotDeleteSchedule() {
        when(reads.find(99, 2, 3)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99, 2, 3)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(writes);
    }

    private ScheduleRequest request(int dose) {
        return new ScheduleRequest(TODAY, TODAY.plusDays(6), List.of(LocalTime.of(7, 0)), dose, null);
    }

    private void readable(LocalDate start, LocalDate end) {
        when(reads.find(7, 2, 3)).thenReturn(Optional.of(new MedicationTarget(3, 2, "엄마", "PARENT", "테스트약",
                1, "식전", start, end, start == null ? null : "07:00", LocalDateTime.of(2026, 10, 18, 12, 0))));
    }

    private MyDrug writable(LocalDate start, LocalDate end) {
        readable(start, end);
        Drug catalogDrug = Drug.create("fixture", "테스트약", "테스트제약", Drug.OTC, "fixture", null, null, null,
                "원형", "흰색", null, null, "정제", "fixture", LocalDateTime.of(2026, 10, 18, 0, 0));
        MyDrug drug = MyDrug.create(2L, catalogDrug, DrugSource.SEARCH, 1, "식전", start, end,
                List.of(LocalTime.of(7, 0)));
        ReflectionTestUtils.setField(drug, "myDrugId", 3L);
        when(writes.findForUpdate(2, 3)).thenReturn(Optional.of(drug));
        return drug;
    }
}
