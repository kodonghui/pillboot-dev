package org.semiproject.pillbadeumserver.image.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 처방전 한 줄의 투약 값이 일정 저장 규칙(1회 1~10개·하루 1~3번·1~365일)에 그대로 들어가는지. */
class PrescriptionDoseTests {
    @Test
    void emptyCellsMeanNoReading() {
        assertThat(PrescriptionDose.of(" ", null, null)).isNull();
        assertThat(PrescriptionDose.of("1", null, null)).isEqualTo(new PrescriptionDose("1", null, null));
    }

    @Test
    void onlyWholeDosesWithinTheScheduleRulesCanBecomeASchedule() {
        assertThat(new PrescriptionDose("1", 3, 5).schedulable()).isTrue();
        assertThat(new PrescriptionDose("1.0", 2, 3).wholeDose()).isEqualTo(1);
        assertThat(new PrescriptionDose("0.5", 3, 5).schedulable()).isFalse();
        assertThat(new PrescriptionDose("0.5", 3, 5).wholeDose()).isNull();
        assertThat(new PrescriptionDose("1", 4, 5).schedulable()).isFalse();
        assertThat(new PrescriptionDose("11", 1, 5).schedulable()).isFalse();
        assertThat(new PrescriptionDose("1", 1, 366).schedulable()).isFalse();
        assertThat(new PrescriptionDose("1", null, 5).schedulable()).isFalse();
    }

    // QA 2026-11-08: 처방전의 시럽 '20'이 '1회 20개'로 보였고, '10'이었다면 1회 10개 일정이 될 뻔함.
    @Test
    void syrupDosesAreMillilitresAndBecomeOneDoseSchedules() {
        var syrup = new PrescriptionDose("20", 3, 5).inMilliliters();
        assertThat(syrup).isEqualTo(new PrescriptionDose("20mL", 3, 5));
        assertThat(syrup.liquidAmount()).isEqualTo("20mL");
        assertThat(syrup.wholeDose()).isNull();
        assertThat(syrup.schedulable()).isTrue();
        assertThat(new PrescriptionDose("7.50", 2, 3).inMilliliters().dosePerTime()).isEqualTo("7.5mL");
        assertThat(new PrescriptionDose("20mL", 3, 5).inMilliliters().dosePerTime()).as("이미 단위가 있으면 그대로").isEqualTo("20mL");
        assertThat(new PrescriptionDose(null, 3, 5).inMilliliters().dosePerTime()).isNull();
        assertThat(new PrescriptionDose("20mL", 4, 5).schedulable()).as("하루 횟수는 같은 규칙").isFalse();
        assertThat(new PrescriptionDose("20", 3, 5).liquidAmount()).isNull();
        assertThat(new PrescriptionDose("20", 3, 5).schedulable()).as("단위 없는 20은 1회 20개라 규칙 밖").isFalse();
    }

    // PR #77 Codex 리뷰: 칸에 개수 단위가 적혔다는 표시(counted)는 판독 안에서만 쓰고 사진 응답 JSON에는 내보내지 않는다
    @Test
    void theCountedMarkStaysOutOfTheJson() {
        var counted = PrescriptionDose.of("1", 3, 3, true);
        assertThat(counted.counted()).isTrue();
        assertThat(PrescriptionDose.of(null, 3, 3, true).counted()).as("1회 투약량이 없으면 표시도 없음").isFalse();
        assertThat(JsonMapper.builder().build().writeValueAsString(counted))
                .isEqualTo("{\"dosePerTime\":\"1\",\"dailyCount\":3,\"totalDays\":3}");
    }
}
