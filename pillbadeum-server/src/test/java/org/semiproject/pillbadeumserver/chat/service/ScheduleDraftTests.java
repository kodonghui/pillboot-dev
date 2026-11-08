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
        var proposed = ScheduleDraft.proposed(said(null, null, null, List.of("08:00"), null, 1, null), MONTH, 2, TODAY);
        assertThat(proposed).isEqualTo(new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                List.of("08:00"), 1, "식후 30분"));
    }

    @Test
    void onlyTheDoseChangesTheDoseAndKeepsTheTimes() {
        var proposed = ScheduleDraft.proposed(said(null, null, null, null, null, 1, null), MONTH, 2, TODAY);
        assertThat(proposed.doseTimes()).containsExactly("07:00", "11:00", "17:00");
        assertThat(proposed.dosePerTime()).isEqualTo(1);
        assertThat(proposed.startDate()).isEqualTo(MONTH.startDate());
    }

    @Test
    void periodRules() {
        assertThat(ScheduleDraft.proposed(said(null, null, 7, null, null, null, null), MONTH, 2, TODAY))
                .as("기간만 말하면 오늘부터").extracting(ScheduleValues::startDate, ScheduleValues::endDate)
                .containsExactly(TODAY, LocalDate.of(2026, 11, 9));
        assertThat(ScheduleDraft.proposed(said(null, "2026-11-20", null, null, null, null, null), MONTH, 2, TODAY))
                .as("종료일만 말하면 시작일은 그대로").extracting(ScheduleValues::startDate, ScheduleValues::endDate)
                .containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20));
        assertThat(ScheduleDraft.proposed(said("2026-11-05", null, 3, null, null, null, null), MONTH, 2, TODAY))
                .extracting(ScheduleValues::startDate, ScheduleValues::endDate)
                .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 7));
    }

    // QA 2026-11-08 시나리오 01: '저녁 약은 7시 말고 9시로'가 아침·점심 시각을 지우고 하루 1번(21:00)으로 바꿨음.
    @Test
    void namingAMealChangesOnlyThatMealsTime() {
        var three = ScheduleDraft.stored(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "07:00,12:00,19:00", 1, null);
        var evening = ScheduleDraft.slotEdit("저녁 약은 7시 말고 9시로 바꿔 줘",
                said(null, null, null, List.of("21:00"), null, null, null), three);
        assertThat(evening.doseTimes()).containsExactly("07:00", "12:00", "21:00");
        assertThat(evening.dailyCount()).isNull();
        assertThat(ScheduleDraft.proposed(evening, three, 1, TODAY).doseTimes()).containsExactly("07:00", "12:00", "21:00");
        assertThat(ScheduleDraft.slotEdit("아침은 8시, 저녁은 8시로", said(null, null, null, List.of("20:00", "08:00"), null, null, null),
                three).doseTimes()).as("두 끼를 말하면 그 두 시각").containsExactly("08:00", "12:00", "20:00");
        assertThat(ScheduleDraft.slotEdit("점심 약 1시로", said(null, null, null, List.of("13:00"), null, 2, null), MONTH))
                .as("말한 1회 개수는 그대로 둠").extracting(ParsedCommand::doseTimes, ParsedCommand::dosePerTime)
                .containsExactly(List.of("07:00", "13:00", "17:00"), 2);
    }

    @Test
    void sayingOnlyOneMealOrNoMealKeepsTheSaidTimes() {
        var three = ScheduleDraft.stored(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "07:00,12:00,19:00", 1, null);
        var nine = said(null, null, null, List.of("21:00"), null, null, null);
        for (String text : List.of("저녁에만 9시로", "저녁만 먹을게 9시", "하루 한 번 저녁 9시", "한 번씩만 저녁 9시",
                "아침 점심 빼고 저녁 9시", "9시로 바꿔 줘"))
            assertThat(ScheduleDraft.slotEdit(text, nine, three)).as(text).isSameAs(nine);
        var mornings = ScheduleDraft.stored(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "07:00,09:00", 1, null);
        assertThat(ScheduleDraft.slotEdit("저녁 9시로", nine, mornings)).as("저녁 시각이 없으면 그대로").isSameAs(nine);
        assertThat(ScheduleDraft.slotEdit("저녁 9시로", nine, NONE)).as("일정 없는 약").isSameAs(nine);
        var both = said(null, null, null, List.of("08:00", "21:00"), null, null, null);
        assertThat(ScheduleDraft.slotEdit("아침 8시하고 9시", both, three)).as("말한 끼와 시각 수가 다르면 그대로").isSameAs(both);
        assertThat(ScheduleDraft.slotEdit("하루 두 번 아침 8시 저녁 9시", both, three)).as("하루 횟수를 말함").isSameAs(both);
        assertThat(ScheduleDraft.slotEdit("2번 먹을게 아침 8시 저녁 9시", both, three)).isSameAs(both);
        // QA 2026-11-08 다시 돌림 시나리오 14: '둘 다 아침 8시 저녁 8시로'는 하루 두 번(카드에 '3번에서 2번으로' 줄)
        assertThat(ScheduleDraft.slotEdit("아침 8시 저녁 9시", both, three)).as("때를 늘어놓으면 그 시각들로").isSameAs(both);
        assertThat(ScheduleDraft.slotEdit("둘 다 아침 8시 저녁 8시로 바꿔줘", both, three)).isSameAs(both);
        assertThat(ScheduleDraft.slotEdit("아침엔 8시 저녁엔 9시", both, three).doseTimes()).as("때마다 '엔'이면 그 때만")
                .containsExactly("08:00", "12:00", "21:00");
        assertThat(ScheduleDraft.slotEdit("아침 7시 말고 8시, 저녁 7시 말고 9시", both, three).doseTimes()).as("'말고'로 고침")
                .containsExactly("08:00", "12:00", "21:00");
        assertThat(ScheduleDraft.slotEdit("아침은 8시 저녁 9시", both, three)).as("한 때에만 '은'").isSameAs(both);
    }

    // 2026-11-08 QA 시나리오 01 다시 돌림: '저녁 약은 7시 말고 9시로 바꿔줘'에 어느 약이냐고 되물음(두 번 중 두 번)
    @Test
    void aSlotGroupIsTheDrugsTakenAtThatTime() {
        assertThat(ScheduleDraft.slotGroup("저녁 약은 7시 말고 9시로 바꿔줘")).isEqualTo("저녁");
        assertThat(ScheduleDraft.slotGroup("아침에 먹는 약 8시로 해 줘")).isEqualTo("아침");
        assertThat(ScheduleDraft.slotGroup("점심약 1시로")).isEqualTo("점심");
        assertThat(ScheduleDraft.slotGroup("아침 약속이 있어서 저녁 9시로")).as("약속").isNull();
        assertThat(ScheduleDraft.slotGroup("저녁 약국 들르고 9시로")).as("약국").isNull();
        // PR #77 Codex 리뷰: '저녁 약으로'는 옮겨 갈 곳이라 약목록 전부를 저녁에 먹던 약으로 좁히지 않음
        assertThat(ScheduleDraft.slotGroup("약목록 전부 저녁 약으로 옮겨줘, 9시에만 먹을게")).as("옮겨 갈 곳").isNull();
        assertThat(ScheduleDraft.slotGroup("아침 약을 저녁 약으로 바꿔줘")).isEqualTo("아침");
        assertThat(ScheduleDraft.slotGroup("저녁약 9시로")).isEqualTo("저녁");
        assertThat(ScheduleDraft.slotGroup("아침 약은 8시, 저녁 약은 9시로")).as("두 때").isNull();
        assertThat(ScheduleDraft.slotGroup("혈압약 저녁 9시로")).as("약 이름 뒤의 때").isNull();
        assertThat(ScheduleDraft.slotGroup(null)).isNull();
        assertThat(ScheduleDraft.takenAt("저녁", "07:00,19:00")).isTrue();
        assertThat(ScheduleDraft.takenAt("저녁", "08:00, 12:00")).isFalse();
        assertThat(ScheduleDraft.takenAt("점심", "08:00, 12:00")).isTrue();
        assertThat(ScheduleDraft.takenAt("아침", "15:00")).isFalse();
        assertThat(ScheduleDraft.takenAt("아침", null)).as("일정 없는 약").isFalse();
        assertThat(ScheduleDraft.takenAt("아침", " ")).isFalse();
    }

    @Test
    void unscheduledDrugUsesTheExistingDefaults() {
        assertThat(ScheduleDraft.proposed(said(null, null, null, List.of("21:00", "08:00"), null, null, null), NONE, 1, TODAY))
                .as("오늘 하루, 말한 시각, 저장된 1회 개수")
                .isEqualTo(new ScheduleValues(TODAY, TODAY, List.of("08:00", "21:00"), 1, null));
        assertThat(ScheduleDraft.proposed(said(null, null, 5, null, null, 2, null), NONE, 1, TODAY).doseTimes())
                .containsExactly("07:00", "12:00", "17:00");
    }

    @Test
    void anUnconfirmedDoseIsEmptyNowAndTheStoredDoseAfter() {
        var unconfirmed = ScheduleDraft.stored(MONTH.startDate(), MONTH.endDate(), "08:00", null, null);
        assertThat(unconfirmed.dosePerTime()).as("내 약 응답처럼 확인되지 않은 개수는 비움").isNull();
        var proposed = ScheduleDraft.proposed(said(null, null, null, List.of("08:00"), null, null, null), unconfirmed, 1, TODAY);
        assertThat(proposed.dosePerTime()).as("말하지 않았으면 저장된 개수를 확정함").isEqualTo(1);
        assertThat(proposed).as("개수만 확인되는 카드라도 지금과 다름").isNotEqualTo(unconfirmed);
        assertThat(ScheduleDraft.proposed(said(null, null, null, null, null, 3, null), unconfirmed, 1, TODAY).dosePerTime())
                .isEqualTo(3);
    }

    @Test
    void dailyCountUsesTheDefaultTimesUnlessItIsTheCurrentCount() {
        assertThat(ScheduleDraft.proposed(said(null, null, null, null, 2, null, null), MONTH, 2, TODAY).doseTimes())
                .containsExactly("07:00", "12:00");
        var twice = ScheduleDraft.stored(MONTH.startDate(), MONTH.endDate(), "08:00,20:00", 1, null);
        assertThat(ScheduleDraft.proposed(said(null, null, null, null, 2, 2, null), twice, 1, TODAY).doseTimes())
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
            assertThatThrownBy(() -> ScheduleDraft.proposed(parsed, MONTH, 2, TODAY)).as(parsed.toString())
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
