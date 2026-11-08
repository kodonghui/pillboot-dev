package org.semiproject.pillbadeumserver.image.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.image.domain.PrescriptionDose;
import tools.jackson.databind.json.JsonMapper;

/**
 * 2026-11-08: 처방전 표 한 줄의 판독 결과(이름·보험코드·1회 투약량·1일 횟수·총 일수·또렷함)를 서버가 어떻게 거르는지.
 * 가상 값만 쓴다. 환자 정보 칸은 판독 스키마에 없다.
 */
class PrescriptionRowReadTests {
    private final JsonMapper json = JsonMapper.builder().build();

    private PhotoIdentificationWorkflow.Read read(Map<String, Object> row) {
        return PhotoIdentificationWorkflow.read(json.valueToTree(row));
    }

    @Test
    void aFullRowKeepsTheCodeAndTheDoseCells() {
        var read = read(Map.of("readName", "가상셀코정", "readMaker", "", "insuranceCode", "640-000-001", "dosePerTime", "1",
                "dailyCount", "3", "totalDays", "5", "clear", true));
        assertThat(read.name()).isEqualTo("가상셀코정");
        assertThat(read.code()).isEqualTo("640000001");
        assertThat(read.dose()).isEqualTo(new PrescriptionDose("1", 3, 5));
        assertThat(read.unclear()).isFalse();
    }

    @Test
    void aCodeAloneIsEnoughAndIsShownAsTheName() {
        var read = read(Map.of("readName", "", "insuranceCode", "640000001", "dosePerTime", "", "dailyCount", "", "totalDays", ""));
        assertThat(read.readName()).isEqualTo("보험코드 640000001");
        assertThat(read.dose()).isNull();
    }

    @Test
    void rowsWithoutANameOrCodeAndOutOfRangeCellsAreDropped() {
        assertThat(read(Map.of("readName", "가", "insuranceCode", "12345"))).isNull();
        var read = read(Map.of("readName", "가상록정", "insuranceCode", "", "dosePerTime", "0", "dailyCount", "99",
                "totalDays", "400", "clear", false));
        assertThat(read.code()).isNull();
        assertThat(read.dose()).isNull();
        assertThat(read.unclear()).isTrue();
        assertThat(read(Map.of("readName", "가상시럽", "dosePerTime", "0.5", "dailyCount", "3", "totalDays", "3")).dose())
                .isEqualTo(new PrescriptionDose("0.5", 3, 3));
    }
}
