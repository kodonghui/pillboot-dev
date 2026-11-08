package org.semiproject.pillbadeumserver.image.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
