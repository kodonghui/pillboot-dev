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
     * 넣을 수 없어 false다(반 알을 1개로 바꾸지 않음). 2026-11-08 QA: 시럽의 1회 양('20mL')은 개수가 아니라 1회 1개와 복용 때 칸의
     * '1회 20mL'로 넣으므로(약 여러 개 일정과 같은 규칙) 하루 횟수·일수만 맞으면 true다.
     */
    public boolean schedulable() {
        Integer dose = wholeDose();
        boolean amount = liquidAmount() != null || dose != null && dose >= 1 && dose <= 10;
        return amount && dailyCount != null && dailyCount >= 1 && dailyCount <= 3
                && totalDays != null && totalDays >= 1 && totalDays <= 365;
    }

    /** 시럽처럼 mL로 읽은 1회 양('20mL'). 개수면 null. */
    public String liquidAmount() {
        return dosePerTime != null && dosePerTime.matches("[0-9]{1,3}(?:\\.[0-9]{1,2})?mL") ? dosePerTime : null;
    }

    /**
     * 2026-11-08 QA(처방전의 코대원에스시럽 '20'이 '1회 20개'로 보이고, '10'이었다면 1회 10개 일정으로 들어갈 뻔함): 시럽·현탁액처럼
     * 물약 품목이면 처방전의 1회 투약량은 mL라 단위를 붙인다. 이미 단위가 있거나 숫자가 아니면 그대로다.
     */
    public PrescriptionDose inMilliliters() {
        if (dosePerTime == null || !dosePerTime.matches("[0-9]{1,3}(?:\\.[0-9]{1,2})?")) return this;
        return new PrescriptionDose(new BigDecimal(dosePerTime).stripTrailingZeros().toPlainString() + "mL", dailyCount,
                totalDays);
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
