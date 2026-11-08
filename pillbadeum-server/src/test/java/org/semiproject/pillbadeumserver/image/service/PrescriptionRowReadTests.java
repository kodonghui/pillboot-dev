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

    // QA 2026-11-08: 시럽 칸을 '20ml'로 읽으면 숫자 모양이 아니라 버려졌음. 개수 단위는 떼고, 적힌 물약 단위는 mL로 남긴다.
    // PR #77 Codex 리뷰: 물약 단위까지 떼면 이름이 '시럽'이 아닌 현탁액의 '5mL'가 1회 5개로 일정에 들어갈 수 있었음.
    @Test
    void aCountUnitIsDroppedAndAWrittenLiquidUnitIsKeptAsMillilitres() {
        assertThat(read(Map.of("readName", "가상기침시럽", "dosePerTime", "20ml", "dailyCount", "3", "totalDays", "5")).dose())
                .isEqualTo(new PrescriptionDose("20mL", 3, 5));
        var suspension = read(Map.of("readName", "가상해열현탁액", "dosePerTime", "5 cc", "dailyCount", "3", "totalDays", "3")).dose();
        assertThat(suspension).as("이름과 관계없이 mL").isEqualTo(new PrescriptionDose("5mL", 3, 3));
        assertThat(suspension.liquidAmount()).isEqualTo("5mL");
        assertThat(suspension.wholeDose()).as("1회 5개로 읽지 않음").isNull();
        assertThat(read(Map.of("readName", "가상해열현탁액", "dosePerTime", "7.25mL", "dailyCount", "3", "totalDays", "3")).dose())
                .isEqualTo(new PrescriptionDose("7.25mL", 3, 3));
        assertThat(read(Map.of("readName", "가상해열현탁액", "dosePerTime", "5.0㎖", "dailyCount", "3", "totalDays", "3")).dose())
                .isEqualTo(new PrescriptionDose("5mL", 3, 3));
        assertThat(read(Map.of("readName", "가상셀코정", "dosePerTime", "1 정", "dailyCount", "2", "totalDays", "3")).dose())
                .isEqualTo(new PrescriptionDose("1", 2, 3, true));
        assertThat(read(Map.of("readName", "가상셀코캡슐", "dosePerTime", "2C", "dailyCount", "2", "totalDays", "3")).dose())
                .isEqualTo(new PrescriptionDose("2", 2, 3, true));
        // PR #77 Codex 리뷰: 시럽 줄의 '1포'를 '1'로 떼면 품목명이 시럽이라 '1mL'가 됐음. 적힌 개수 단위는 개수로 남긴다.
        var sachet = read(Map.of("readName", "가상기침시럽", "dosePerTime", "1포", "dailyCount", "3", "totalDays", "3")).dose();
        assertThat(sachet).isEqualTo(new PrescriptionDose("1", 3, 3, true));
        assertThat(sachet.inMilliliters()).as("개수로 적혔으면 mL로 보지 않음").isSameAs(sachet);
        assertThat(read(Map.of("readName", "가상기침시럽", "dosePerTime", "10", "dailyCount", "3", "totalDays", "3")).dose())
                .as("단위가 없으면 개수인지 모름").isEqualTo(new PrescriptionDose("10", 3, 3));
        assertThat(read(Map.of("readName", "가상셀코정", "dosePerTime", "한 알", "dailyCount", "2", "totalDays", "3")).dose())
                .as("숫자가 아니면 버림").isEqualTo(new PrescriptionDose(null, 2, 3));
    }
}
