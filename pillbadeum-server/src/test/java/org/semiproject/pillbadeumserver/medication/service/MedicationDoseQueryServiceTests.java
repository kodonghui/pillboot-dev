package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationDtos.DoseItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationDoseQueryServiceTests {
    @Mock MedicationRepository reads;
    @InjectMocks MedicationDoseQueryService service;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);

    @Test
    void wholeDayExpandsStoredTimesAndSortsByTimeProfileAndMedication() {
        when(reads.calendar(5, DATE, DATE)).thenReturn(List.of(
                target(27, 14, DATE, DATE.plusDays(3), "17:00,07:00"),
                target(26, 13, DATE.minusDays(1), DATE, "07:00"),
                target(25, 13, DATE.minusDays(1), DATE.plusDays(3), "11:00,07:00")));

        var result = service.get(5, DATE, null);

        assertThat(result.date()).isEqualTo(DATE);
        assertThat(result.time()).isNull();
        assertThat(result.doses()).extracting(DoseItem::time).containsExactly("07:00", "07:00", "07:00", "11:00", "17:00");
        assertThat(result.doses()).extracting(DoseItem::myDrugId).containsExactly(25L, 26L, 27L, 25L, 27L);
        assertThat(result.doses().getFirst().dosePerTime()).isEqualTo(2);
        assertThat(result.doses().getFirst().timing()).isEqualTo("식후");
    }

    @Test
    void selectedTimeIncludesAllProfilesTakingMedicationAtThatExactMinute() {
        when(reads.calendar(5, DATE, DATE)).thenReturn(List.of(
                target(25, 13, DATE, DATE, "07:00,11:00"),
                target(27, 14, DATE, DATE, "07:00,17:00")));
        var result = service.get(5, DATE, "07:00");
        assertThat(result.time()).isEqualTo("07:00");
        assertThat(result.doses()).extracting(DoseItem::myDrugId).containsExactly(25L, 27L);
        assertThat(result.doses()).allMatch(dose -> dose.time().equals("07:00"));
    }

    @Test
    void deletedAndOutsideSchedulesAreNotExpandedAndLastDayIsIncluded() {
        when(reads.calendar(5, DATE, DATE)).thenReturn(List.of(
                target(25, 13, null, null, null),
                target(26, 13, DATE.plusDays(1), DATE.plusDays(7), "07:00"),
                target(27, 14, DATE.minusDays(7), DATE.minusDays(1), "07:00"),
                target(28, 14, DATE.minusDays(7), DATE, "07:00")));
        assertThat(service.get(5, DATE, null).doses()).extracting(DoseItem::myDrugId).containsExactly(28L);
    }

    @Test
    void noMedicationsAtSelectedTimeReturnsAnEmptyList() {
        when(reads.calendar(5, DATE, DATE)).thenReturn(List.of(target(25, 13, DATE, DATE, "07:00")));
        assertThat(service.get(5, DATE, "08:00").doses()).isEmpty();
    }

    @Test
    void malformedTimeOrMissingDateIsRejectedBeforeReading() {
        for (String time : List.of("7:00", "24:00", "07:60", "07:00:30", "", "tomorrow")) {
            assertThatThrownBy(() -> service.get(5, DATE, time)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.get(5, null, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reads);
    }

    private MedicationTarget target(long drugId, long profileId, LocalDate start, LocalDate end, String times) {
        return new MedicationTarget(drugId, profileId, "프로필" + profileId, "FAMILY", "테스트약", 2, "식후",
                start, end, times, LocalDateTime.of(2026, 10, 19, 9, 0));
    }
}
