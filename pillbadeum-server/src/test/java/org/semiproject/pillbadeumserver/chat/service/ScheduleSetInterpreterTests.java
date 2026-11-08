package org.semiproject.pillbadeumserver.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import tools.jackson.databind.json.JsonMapper;

/** 여러 약 일정 해석 결과를 약마다의 값으로 바꾸는 규칙(모델 출력은 가짜). */
class ScheduleSetInterpreterTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<MedicationTarget> targets = List.of(target(30L, "가상셀코정"), target(35L, "가상기침시럽"));

    private static MedicationTarget target(long id, String name) {
        return new MedicationTarget(id, 10L, "", "SELF", name, 1, null, null, null, null, LocalDateTime.of(2026, 11, 8, 9, 0), false);
    }

    private static Map<String, Object> result(List<Map<String, Object>> exceptions) {
        var map = new java.util.HashMap<String, Object>();
        map.put("intent", "CREATE_SCHEDULE");
        map.put("startDate", "2026-11-08");
        map.put("endDate", null);
        map.put("durationDays", 3);
        map.put("doseTimes", List.of("09:00", "13:00", "19:00"));
        map.put("dailyCount", 3);
        map.put("dosePerTime", 1);
        map.put("timing", "식후 30분");
        map.put("exceptions", exceptions);
        return map;
    }

    @Test
    void sharedValuesGoToEveryDrugAndTheSyrupAmountStaysOutOfTheCount() {
        var parsed = ScheduleSetInterpreter.commands(json.valueToTree(result(List.of(
                Map.of("myDrugId", 35, "amountText", "20미리"),
                Map.of("myDrugId", 99, "dosePerTime", 5)))), targets);
        assertThat(parsed.intent()).isEqualTo("CREATE_SCHEDULE");
        assertThat(parsed.commands()).hasSize(2);
        var tablet = parsed.commands().get(0);
        assertThat(tablet.command().myDrugId()).isEqualTo(30L);
        assertThat(tablet.command().doseTimes()).containsExactly("09:00", "13:00", "19:00");
        assertThat(tablet.command().dosePerTime()).isEqualTo(1);
        assertThat(tablet.command().timing()).isEqualTo("식후 30분");
        assertThat(tablet.amount()).isNull();
        var syrup = parsed.commands().get(1);
        assertThat(syrup.command().myDrugId()).isEqualTo(35L);
        assertThat(syrup.command().dosePerTime()).isEqualTo(1);
        assertThat(syrup.amount()).isEqualTo("20mL");
        assertThat(syrup.command().durationDays()).isEqualTo(3);
        assertThat(syrup.command().startDate()).isEqualTo("2026-11-08");
    }

    @Test
    void unsupportedRequestsHaveNoCommandsAndBrokenOutputIsAnError() {
        assertThat(ScheduleSetInterpreter.commands(json.valueToTree(Map.of("intent", "UNSUPPORTED")), targets).commands()).isEmpty();
        assertThatThrownBy(() -> ScheduleSetInterpreter.commands(json.valueToTree(Map.of("value", 1)), targets))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyShortAmountsWithAUnitAreKept() {
        assertThat(ScheduleSetInterpreter.amount("20 ml")).isEqualTo("20mL");
        assertThat(ScheduleSetInterpreter.amount("7.5mL")).isEqualTo("7.5mL");
        assertThat(ScheduleSetInterpreter.amount("5cc")).isEqualTo("5cc");
        assertThat(ScheduleSetInterpreter.amount("한 숟가락")).isNull();
        assertThat(ScheduleSetInterpreter.amount("2000mL")).isNull();
        assertThat(LocalDate.parse("2026-11-08")).isNotNull();
    }
}
