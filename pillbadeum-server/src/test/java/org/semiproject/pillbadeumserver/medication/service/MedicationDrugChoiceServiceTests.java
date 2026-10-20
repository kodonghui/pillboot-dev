package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationDrugChoiceServiceTests {
    @Mock MedicationRepository reads;
    @InjectMocks MedicationDrugChoiceService service;

    @Test
    void includesBothScheduledAndUnscheduledMedicationsForTheSelectedProfile() {
        when(reads.ownsProfile(5, 13)).thenReturn(true);
        when(reads.drugs(5, 13)).thenReturn(List.of(
                target(25, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), "07:00,17:00"),
                target(26, null, null, null)));
        when(reads.pushEnabled(5)).thenReturn(true);

        var result = service.get(5, 13);

        assertThat(result.drugs()).extracting(drug -> drug.myDrugId()).containsExactly(25L, 26L);
        assertThat(result.drugs().getFirst().hasSchedule()).isTrue();
        assertThat(result.drugs().getFirst().doseTimes()).containsExactly("07:00", "17:00");
        var unscheduled = result.drugs().get(1);
        assertThat(unscheduled.hasSchedule()).isFalse();
        assertThat(unscheduled.startDate()).isNull();
        assertThat(unscheduled.endDate()).isNull();
        assertThat(unscheduled.doseTimes()).isEmpty();
        assertThat(unscheduled.dosePerTime()).isEqualTo(2);
        assertThat(unscheduled.timing()).isEqualTo("식후");
        assertThat(result.drugs()).allMatch(drug -> drug.profileId() == 13 && drug.pushEnabled());
        verify(reads, times(1)).pushEnabled(5);
    }

    @Test
    void ownedProfileWithoutMedicationsReturnsAnEmptyList() {
        when(reads.ownsProfile(5, 13)).thenReturn(true);
        when(reads.drugs(5, 13)).thenReturn(List.of());
        assertThat(service.get(5, 13).drugs()).isEmpty();
    }

    @Test
    void foreignAndMissingProfilesAreRejectedBeforeListingMedications() {
        assertThatThrownBy(() -> service.get(5, 99)).isInstanceOf(NotFoundException.class);
        verify(reads).ownsProfile(5, 99);
        verifyNoMoreInteractions(reads);
    }

    private MedicationTarget target(long drugId, LocalDate start, LocalDate end, String times) {
        return new MedicationTarget(drugId, 13, "관리자", "SELF", "테스트약", 2, "식후", start, end,
                times, LocalDateTime.of(2026, 10, 19, 9, 0));
    }
}
