package org.semiproject.pillbadeumserver.image.domain;

import java.math.BigDecimal;

/**
 * 2026-11-08: 처방전 표에서 약 한 줄의 1회 투약량·1일 투여 횟수·총 투약 일수(인쇄된 그대로 읽은 값).
 * 1회 투약량은 0.5처럼 소수일 수 있어 글자로 둔다. 읽지 못한 칸은 null이다. 약목록에는 확정 뒤에만 쓴다.
 */
public record PrescriptionDose(String dosePerTime, Integer dailyCount, Integer totalDays) {
    /** 세 칸이 모두 비었으면 null로 본다. */
    public static PrescriptionDose of(String dosePerTime, Integer dailyCount, Integer totalDays) {
        String dose = dosePerTime == null || dosePerTime.isBlank() ? null : dosePerTime.strip();
        if (dose == null && dailyCount == null && totalDays == null) return null;
        return new PrescriptionDose(dose, dailyCount, totalDays);
    }

    /**
     * 일정 저장 규칙(1회 1~10개, 하루 1~3번, 1~365일)에 그대로 들어가는 값인지. 0.5처럼 정수가 아닌 1회 투약량은 일정 칸에
     * 넣을 수 없어 false다(반 알을 1개로 바꾸지 않음).
     */
    public boolean schedulable() {
        Integer dose = wholeDose();
        return dose != null && dose >= 1 && dose <= 10 && dailyCount != null && dailyCount >= 1 && dailyCount <= 3
                && totalDays != null && totalDays >= 1 && totalDays <= 365;
    }

    /** 1회 투약량이 정수면 그 값, 아니면 null('1'·'1.0'·'2' → 1·1·2, '0.5' → null). */
    public Integer wholeDose() {
        if (dosePerTime == null || !dosePerTime.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
        var value = new BigDecimal(dosePerTime);
        try {
            return value.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException notWhole) {
            return null;
        }
    }
}
