package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationCalendarServiceTests {
    @Mock MedicationRepository reads;
    @InjectMocks MedicationCalendarService service;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 19);
    private static final LocalDate TO = LocalDate.of(2026, 10, 25);

    @Test
    void returnsMultipleProfilesWithStoredPeriodsAndTimesInsteadOfDailyCopies() {
        when(reads.calendar(5, FROM, TO)).thenReturn(List.of(
                target(25, 13, "관리자", FROM.minusDays(10), TO.plusDays(10), "07:00,17:00"),
                target(27, 14, "부모님", FROM, TO, "11:00")));
        when(reads.pushEnabled(5)).thenReturn(true);

        var result = service.get(5, FROM, TO);

        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);
        assertThat(result.schedules()).hasSize(2);
        assertThat(result.schedules()).extracting(s -> s.profileId()).containsExactly(13L, 14L);
        assertThat(result.schedules()).extracting(s -> s.myDrugId()).containsExactly(25L, 27L);
        assertThat(result.schedules().getFirst().startDate()).isEqualTo(FROM.minusDays(10));
        assertThat(result.schedules().getFirst().endDate()).isEqualTo(TO.plusDays(10));
        assertThat(result.schedules().getFirst().doseTimes()).containsExactly("07:00", "17:00");
        assertThat(result.schedules()).allMatch(s -> s.hasSchedule() && s.pushEnabled());
        verify(reads, times(1)).pushEnabled(5);
    }

    @Test
    void emptyCalendarAndSingleDayRangeAreValid() {
        when(reads.calendar(5, FROM, FROM)).thenReturn(List.of());
        assertThat(service.get(5, FROM, FROM).schedules()).isEmpty();
    }

    @Test
    void reversedAndMissingDatesAreRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.get(5, TO, FROM)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(5, null, TO)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(5, FROM, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reads);
    }

    private MedicationTarget target(long drugId, long profileId, String profileName,
                                    LocalDate start, LocalDate end, String times) {
        return new MedicationTarget(drugId, profileId, profileName, "FAMILY", "테스트약", 1, "식후",
                start, end, times, LocalDateTime.of(2026, 10, 19, 9, 0));
    }
}
