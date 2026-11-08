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

    // PR #77 Codex 리뷰: 읽지 못한 양은 앞 양을 그대로 두지 않고 거절함. 정제·캡슐의 '200미리'는 함량이라 양으로 보지 않음
    @Test
    void anUnreadableAmountIsRejectedAndATabletStrengthIsNotAnAmount() {
        assertThatThrownBy(() -> ScheduleSetInterpreter.commands(json.valueToTree(result(List.of(
                Map.of("myDrugId", 35, "amountText", "반 스푼")))), targets))
                .isInstanceOf(ScheduleDraft.Rejected.class)
                .hasMessage("‘가상기침시럽’의 1회 양을 일정에 적을 수 없어요(‘반 스푼’). ‘5mL’처럼 숫자와 단위로 말해 주세요.");
        var counted = ScheduleSetInterpreter.commands(json.valueToTree(result(List.of(
                Map.of("myDrugId", 35, "amountText", "한 알")))), targets);
        assertThat(counted.commands().get(1).amount()).as("개수는 양이 아님").isNull();
        var capsule = List.of(target(36L, "가상스텐캡슐200밀리그램(아세틸시스테인)"));
        var strength = ScheduleSetInterpreter.commands(json.valueToTree(result(List.of(
                Map.of("myDrugId", 36, "amountText", "200mL")))), capsule);
        assertThat(strength.commands().getFirst().amount()).isNull();
        assertThat(strength.commands().getFirst().command().dosePerTime()).isEqualTo(1);
        assertThat(ScheduleSetInterpreter.byVolume("가상기침시럽")).isTrue();
        assertThat(ScheduleSetInterpreter.byVolume("가상현탁액")).isTrue();
        assertThat(ScheduleSetInterpreter.byVolume("가상과립")).as("가루약은 g로 먹을 수 있음").isTrue();
        assertThat(ScheduleSetInterpreter.byVolume("가상셀코정")).isFalse();
        assertThat(ScheduleSetInterpreter.byVolume("가상스텐캡슐200밀리그램(아세틸시스테인)")).isFalse();
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
        // PR #77 Codex 리뷰: 처방전 일정과 같게 소수 둘째 자리까지
        assertThat(ScheduleSetInterpreter.amount("7.25mL")).isEqualTo("7.25mL");
        assertThat(ScheduleSetInterpreter.amount("7.255mL")).isNull();
        assertThat(ScheduleSetInterpreter.amount("20mL씩")).isEqualTo("20mL");
        assertThat(ScheduleSetInterpreter.amount("20밀리리터")).isEqualTo("20mL");
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
        assertThat(ScheduleSetInterpreter.liquidAmount("가상기침시럽 7.25미리씩")).isEqualTo("7.25mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("20밀리리터로")).isEqualTo("20mL");
        // PR #77 Codex 리뷰: '5mL 말고 10mL로'는 양이 둘이라 null이 되어 앞 양을 그대로 두었음. '로'가 붙은 양이 바꿀 양이고,
        // '말고·대신·에서·아니라'가 붙은 양은 바꾸기 전 양이다. 그래도 둘 이상이면 어느 양인지 모르니 모두 돌려준다(카드 대신 되물음).
        assertThat(ScheduleSetInterpreter.liquidAmount("가상기침시럽 5mL 말고 10mL로 바꿔줘")).isEqualTo("10mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("5미리에서 7.5미리로 늘려 줘")).isEqualTo("7.5mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("5mL를 10mL씩으로 바꿔")).isEqualTo("10mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("10mL 대신 5mL")).isEqualTo("5mL");
        assertThat(ScheduleSetInterpreter.liquidAmount("5mL가 아니라 10mL")).isEqualTo("10mL");
        assertThat(ScheduleSetInterpreter.liquidAmounts("시럽은 20미리, 물약은 10미리")).containsExactly("20mL", "10mL");
        assertThat(ScheduleSetInterpreter.liquidAmounts("아침엔 5mL로 저녁엔 10mL로")).as("때마다 다른 양은 한 칸에 못 적음")
                .containsExactly("5mL", "10mL");
        assertThat(ScheduleSetInterpreter.liquidAmounts("20미리 그러니까 20ml")).containsExactly("20mL");
        assertThat(ScheduleSetInterpreter.liquidAmounts("하루 3번")).isEmpty();
        // PR #77 Codex 리뷰: 한 약 일정에서 읽지 못한 양('7.255mL')을 말하지 않은 것처럼 보고 앞 양을 그대로 두었음
        assertThat(ScheduleSetInterpreter.unreadAmount("가상기침시럽 7.255mL로 바꿔")).isEqualTo("7.255mL");
        assertThat(ScheduleSetInterpreter.unreadAmount("두 번 2스푼씩")).isEqualTo("2스푼");
        assertThat(ScheduleSetInterpreter.unreadAmount("1000mL")).isEqualTo("1000mL");
        assertThat(ScheduleSetInterpreter.unreadAmount("10mL로")).isNull();
        assertThat(ScheduleSetInterpreter.unreadAmount("가상정 500밀리그램 한 알")).isNull();
        assertThat(ScheduleSetInterpreter.unreadAmount("물 1.5리터와 함께")).isNull();
        assertThat(ScheduleSetInterpreter.unreadAmount("한 스푼")).as("숫자 없이 말한 양은 되묻지 않음").isNull();

        var counted = new ParsedCommand("CREATE_SCHEDULE", 35L, null, null, null, List.of("08:00"), null, 20, null);
        assertThat(ScheduleSetInterpreter.withLiquid(counted, "20mL").dosePerTime()).as("양의 숫자를 개수로 읽음").isEqualTo(1);
        var none = new ParsedCommand("CREATE_SCHEDULE", 35L, null, null, null, List.of("08:00"), null, null, null);
        assertThat(ScheduleSetInterpreter.withLiquid(none, "7.5mL").dosePerTime()).isEqualTo(1);
        var two = new ParsedCommand("CREATE_SCHEDULE", 35L, null, null, null, List.of("08:00"), null, 2, null);
        assertThat(ScheduleSetInterpreter.withLiquid(two, "20mL")).as("따로 말한 개수는 그대로").isSameAs(two);
        assertThat(ScheduleSetInterpreter.withLiquid(counted, null)).isSameAs(counted);
    }
}
