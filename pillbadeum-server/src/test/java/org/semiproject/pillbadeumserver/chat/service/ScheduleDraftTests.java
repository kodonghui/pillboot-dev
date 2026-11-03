package org.semiproject.pillbadeumserver.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse.ScheduleValues;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.ParsedCommand;

/** V3-CHAT-007: 해석한 값으로 '바뀐 뒤' 일정을 만드는 규칙. 말하지 않은 값은 지금 일정이고, 일정 없는 약은 기존 기본값이다. */
class ScheduleDraftTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final ScheduleValues MONTH = ScheduleDraft.stored(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
            "17:00,07:00,11:00", 2, "식후 30분");
    private static final ScheduleValues NONE = ScheduleDraft.stored(null, null, null, 1, " ");

    private static ParsedCommand said(String start, String end, Integer days, List<String> times, Integer count,
                                      Integer dose, String timing) {
        return new ParsedCommand("CREATE_SCHEDULE", null, start, end, days, times, count, dose, timing);
    }

    @Test
    void storedValuesAreSortedAndAnUnscheduledDrugHasNoDatesOrTimes() {
        assertThat(MONTH.doseTimes()).containsExactly("07:00", "11:00", "17:00");
        assertThat(NONE).isEqualTo(new ScheduleValues(null, null, null, 1, null));
        assertThat(ScheduleDraft.stored(LocalDate.of(2026, 11, 1), null, "08:00", 1, null).doseTimes())
                .as("일부만 있는 값은 일정 없음으로 봄").isNull();
    }

    @Test
    void morningEightOnePillKeepsThePeriodAndTiming() {
        var proposed = ScheduleDraft.proposed(said(null, null, null, List.of("08:00"), null, 1, null), MONTH, TODAY);
        assertThat(proposed).isEqualTo(new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                List.of("08:00"), 1, "식후 30분"));
    }

    @Test
    void onlyTheDoseChangesTheDoseAndKeepsTheTimes() {
        var proposed = ScheduleDraft.proposed(said(null, null, null, null, null, 1, null), MONTH, TODAY);
        assertThat(proposed.doseTimes()).containsExactly("07:00", "11:00", "17:00");
        assertThat(proposed.dosePerTime()).isEqualTo(1);
        assertThat(proposed.startDate()).isEqualTo(MONTH.startDate());
    }

    @Test
    void periodRules() {
        assertThat(ScheduleDraft.proposed(said(null, null, 7, null, null, null, null), MONTH, TODAY))
                .as("기간만 말하면 오늘부터").extracting(ScheduleValues::startDate, ScheduleValues::endDate)
                .containsExactly(TODAY, LocalDate.of(2026, 11, 9));
        assertThat(ScheduleDraft.proposed(said(null, "2026-11-20", null, null, null, null, null), MONTH, TODAY))
                .as("종료일만 말하면 시작일은 그대로").extracting(ScheduleValues::startDate, ScheduleValues::endDate)
                .containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20));
        assertThat(ScheduleDraft.proposed(said("2026-11-05", null, 3, null, null, null, null), MONTH, TODAY))
                .extracting(ScheduleValues::startDate, ScheduleValues::endDate)
                .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 7));
    }

    @Test
    void unscheduledDrugUsesTheExistingDefaults() {
        assertThat(ScheduleDraft.proposed(said(null, null, null, List.of("21:00", "08:00"), null, null, null), NONE, TODAY))
                .as("오늘 하루, 말한 시각, 저장된 1회 개수")
                .isEqualTo(new ScheduleValues(TODAY, TODAY, List.of("08:00", "21:00"), 1, null));
        assertThat(ScheduleDraft.proposed(said(null, null, 5, null, null, 2, null), NONE, TODAY).doseTimes())
                .containsExactly("07:00", "11:00", "17:00");
    }

    @Test
    void dailyCountUsesTheDefaultTimesUnlessItIsTheCurrentCount() {
        assertThat(ScheduleDraft.proposed(said(null, null, null, null, 2, null, null), MONTH, TODAY).doseTimes())
                .containsExactly("07:00", "11:00");
        var twice = ScheduleDraft.stored(MONTH.startDate(), MONTH.endDate(), "08:00,20:00", 1, null);
        assertThat(ScheduleDraft.proposed(said(null, null, null, null, 2, 2, null), twice, TODAY).doseTimes())
                .containsExactly("08:00", "20:00");
    }

    @Test
    void valuesOutsideTheRulesAreRejectedWithAChatNotice() {
        var cases = List.of(
                said(null, null, null, null, null, 11, null),
                said(null, null, null, null, null, 0, null),
                said(null, null, null, List.of("07:00", "08:00", "09:00", "10:00"), null, null, null),
                said(null, null, null, List.of("08:00", "08:00"), null, null, null),
                said(null, null, null, List.of("25:00"), null, null, null),
                said(null, null, null, List.of("8시"), null, null, null),
                said(null, null, null, List.of(), null, null, null),
                said(null, null, null, List.of("08:00"), 2, null, null),
                said(null, null, null, null, 4, null, null),
                said(null, null, 0, null, null, null, null),
                said("2026-12-05", null, null, null, null, null, null),
                said("2026-11-05", "2026-11-04", null, null, null, null, null),
                said("2026-11-05", "2026-11-30", 3, null, null, null, null),
                said("2026-13-01", null, null, null, null, null, null),
                said(null, null, null, null, null, null, "식후 30분 뒤에 물 한 컵과 함께 천천히"));
        for (var parsed : cases) {
            assertThatThrownBy(() -> ScheduleDraft.proposed(parsed, MONTH, TODAY)).as(parsed.toString())
                    .isInstanceOf(ScheduleDraft.Rejected.class).hasMessageEndingWith("요.");
        }
    }

    @Test
    void requestIsTheFullReplacementTheScheduleServiceSaves() {
        var request = ScheduleDraft.request(new ScheduleValues(TODAY, TODAY, List.of("08:00"), 1, null));
        assertThat(request.doseTimes()).containsExactly(LocalTime.of(8, 0));
        assertThat(request.dosePerTime()).isEqualTo(1);
        assertThat(request.timing()).isNull();
    }
}
