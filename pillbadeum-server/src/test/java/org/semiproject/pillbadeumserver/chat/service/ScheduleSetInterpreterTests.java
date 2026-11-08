package org.semiproject.pillbadeumserver.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.ParsedCommand;
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

    // QA 2026-11-08: 한 약 일정에서 '20미리라고'가 1회 20개로 읽혀 거절됐고, '10미리씩'은 1회 10개로 저장됐음.
    @Test
    void aSingleSyrupAmountInTheTextIsOneDoseWithThatAmount() {
        assertThat(ScheduleSetInterpreter.liquidAmount("가상기침시럽 하루 3번 20미리라고")).isEqualTo("20mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("10미리씩 아침 저녁")).isEqualTo("10mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("7.5 ml로")).isEqualTo("7.5mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("5cc씩")).isEqualTo("5cc");
        assertThat(ScheduleSetInterpreter.liquidAmount("20미리 그러니까 20ml")).isEqualTo("20mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("가상정 500밀리그램 한 알")).as("함량은 1회 양이 아님").isNull();
        assertThat(ScheduleSetInterpreter.liquidAmount("200mg 두 알")).isNull();
        assertThat(ScheduleSetInterpreter.liquidAmount("물 1.5리터와 함께")).isNull();
        assertThat(ScheduleSetInterpreter.liquidAmount("시럽은 20미리, 물약은 10미리")).as("양이 여럿이면 어느 약인지 모름").isNull();
        assertThat(ScheduleSetInterpreter.liquidAmount(null)).isNull();

        var counted = new ParsedCommand("CREATE_SCHEDULE", 35L, null, null, null, List.of("08:00"), null, 20, null);
        assertThat(ScheduleSetInterpreter.withLiquid(counted, "20mL").dosePerTime()).as("양의 숫자를 개수로 읽음").isEqualTo(1);
        var none = new ParsedCommand("CREATE_SCHEDULE", 35L, null, null, null, List.of("08:00"), null, null, null);
        assertThat(ScheduleSetInterpreter.withLiquid(none, "7.5mL").dosePerTime()).isEqualTo(1);
        var two = new ParsedCommand("CREATE_SCHEDULE", 35L, null, null, null, List.of("08:00"), null, 2, null);
        assertThat(ScheduleSetInterpreter.withLiquid(two, "20mL")).as("따로 말한 개수는 그대로").isSameAs(two);
        assertThat(ScheduleSetInterpreter.withLiquid(counted, null)).isSameAs(counted);
    }
}
