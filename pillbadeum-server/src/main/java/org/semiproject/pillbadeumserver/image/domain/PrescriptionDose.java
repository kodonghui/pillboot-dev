package org.semiproject.pillbadeumserver.image.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;

/**
 * 2026-11-08: 처방전 표에서 약 한 줄의 1회 투약량·1일 투여 횟수·총 투약 일수(인쇄된 그대로 읽은 값).
 * 1회 투약량은 0.5처럼 소수일 수 있어 글자로 둔다. 읽지 못한 칸은 null이다. 약목록에는 확정 뒤에만 쓴다.
 */
public record PrescriptionDose(String dosePerTime, Integer dailyCount, Integer totalDays, @JsonIgnore boolean counted) {
    /** 단위 없이 읽었거나 저장해 둔 값. 개수인지 물약 양인지는 품목으로 정한다(inMilliliters). */
    public PrescriptionDose(String dosePerTime, Integer dailyCount, Integer totalDays) {
        this(dosePerTime, dailyCount, totalDays, false);
    }

    /** 세 칸이 모두 비었으면 null로 본다. */
    public static PrescriptionDose of(String dosePerTime, Integer dailyCount, Integer totalDays) {
        return of(dosePerTime, dailyCount, totalDays, false);
    }

    /**
     * counted는 1회 투약량 칸에 개수 단위('1포'·'1정')가 적혀 있었다는 뜻이다. 그러면 시럽이어도 mL로 보지 않는다(PR #77 Codex
     * 리뷰: 시럽 줄의 '1포'가 '1mL'가 됐음). 판독에서만 쓰고 응답·저장에는 숫자만 남는다.
     */
    public static PrescriptionDose of(String dosePerTime, Integer dailyCount, Integer totalDays, boolean counted) {
        String dose = dosePerTime == null || dosePerTime.isBlank() ? null : dosePerTime.strip();
        if (dose == null && dailyCount == null && totalDays == null) return null;
        return new PrescriptionDose(dose, dailyCount, totalDays, dose != null && counted);
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
     * 2026-11-08 QA(처방전의 코대원에스시럽 '20'이 '1회 20개'로 보이고, '10'이었다면 1회 10개 일정으로 들어갈 뻔함): 시럽 품목이면
     * 단위 없이 적힌 처방전의 1회 투약량은 mL라 단위를 붙인다(포로 주는 현탁액은 제외, 칸에 mL가 적혀 있으면 판독 때 이미 남김).
     * 이미 단위가 있거나, 칸에 개수 단위가 적혀 있었거나(counted), 숫자가 아니면 그대로다.
     */
    public PrescriptionDose inMilliliters() {
        if (counted || dosePerTime == null || !dosePerTime.matches("[0-9]{1,3}(?:\\.[0-9]{1,2})?")) return this;
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
