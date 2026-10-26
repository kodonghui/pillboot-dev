package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationScheduleWriteRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.ScheduleRequest;
import org.semiproject.pillbadeumserver.medication.service.MedicationScheduleService.ScheduleChange;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationBatchSaveTests {
    @Mock MedicationRepository reads;
    @Mock MedicationScheduleWriteRepository writes;
    @InjectMocks MedicationScheduleService service;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);

    @Test
    void validatesBothThenLocksInStableIdOrderAndChangesOnlyTheTwoExistingRows() {
        owned(3); owned(4);
        var first = entity(3);
        var second = entity(4);
        when(writes.findForUpdate(13, 3)).thenReturn(Optional.of(first));
        when(writes.findForUpdate(13, 4)).thenReturn(Optional.of(second));
        var saved = service.saveBatch(5, 13, List.of(new ScheduleChange(4, request(2)), new ScheduleChange(3, request(1))));
        assertThat(saved).extracting(response -> response.myDrugId()).containsExactly(3L, 4L);
        assertThat(first.getDoseTimes()).containsExactly(LocalTime.NOON);
        assertThat(second.getDoseTimes()).containsExactly(LocalTime.NOON);
        assertThat(first.getDosePerTime()).isEqualTo(1);
        assertThat(second.getDosePerTime()).isEqualTo(2);
        var order = inOrder(reads, writes);
        order.verify(reads).find(5, 13, 3); order.verify(reads).find(5, 13, 4);
        order.verify(writes).findForUpdate(13, 3); order.verify(writes).findForUpdate(13, 4);
        verify(writes, never()).save(any());
    }

    @Test
    void losingTheSecondRowBeforeLockingCannotChangeTheFirstRow() {
        owned(3); owned(4);
        var first = entity(3);
        when(writes.findForUpdate(13, 3)).thenReturn(Optional.of(first));
        when(writes.findForUpdate(13, 4)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveBatch(5, 13, List.of(new ScheduleChange(3, request(2)), new ScheduleChange(4, request(2)))))
                .isInstanceOf(NotFoundException.class);
        assertThat(first.hasSchedule()).isFalse();
        assertThat(first.getDosePerTime()).isEqualTo(1);
        verify(writes, never()).save(any());
    }

    @Test
    void invalidSecondInputIsRejectedBeforeLockingOrChangingAnyActualEntity() {
        owned(3); owned(4);
        var invalid = new ScheduleRequest(DAY, DAY.plusDays(6), List.of(LocalTime.NOON, LocalTime.NOON), 2, null);
        assertThatThrownBy(() -> service.saveBatch(5, 13, List.of(new ScheduleChange(3, request(2)), new ScheduleChange(4, invalid))))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void foreignOrMissingSecondMedicationIsRejectedBeforeLockingTheFirst() {
        owned(3);
        when(reads.find(5, 13, 4)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveBatch(5, 13, List.of(new ScheduleChange(3, request(1)), new ScheduleChange(4, request(1)))))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void duplicateIdsCannotPretendToBeTwoDrugs() {
        assertThatThrownBy(() -> service.saveBatch(5, 13, List.of(new ScheduleChange(3, request(1)), new ScheduleChange(3, request(1)))))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(reads, writes);
    }

    private void owned(long id) {
        when(reads.find(5, 13, id)).thenReturn(Optional.of(new MedicationTarget(id, 13, "본인", "SELF", "합성약" + id,
                1, "식후", null, null, null, null)));
    }
    private MyDrug entity(long id) {
        var drug = MyDrug.createCustom(13L, "합성약" + id);
        ReflectionTestUtils.setField(drug, "myDrugId", id);
        return drug;
    }
    private ScheduleRequest request(int dose) { return new ScheduleRequest(DAY, DAY.plusDays(6), List.of(LocalTime.NOON), dose, "식후"); }
}
