package org.semiproject.pillbadeumserver.drug.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* 설명. V3-DRUG-001: 품목명에서 기본 이름과 함량을 뗀 값 (품목명 원문은 바꾸지 않음)
 *  ----------------------------------------------------------------------------------
 *  예) '알프람정0.25밀리그램(알프라졸람)' → 기본 이름 '알프람정', 원문 '0.25밀리그램', 250μg, 표시 단위 mg
 *      '○○정500/125mg'                → 기본 이름 '○○정', 원문 '500/125mg', 값·단위 없음
 *  ----------------------------------------------------------------------------------
 *  - 단일 성분의 질량 함량만 μg 값과 표시 단위로 정규화함. 복합·농도·활성 단위는 원문만 둠(V3-REQ-37).
 *  - 괄호 안(성분명 설명)은 함량을 찾지 않고 기본 이름에서도 뗌.
 *  - 해석하지 못해도 예외를 던지지 않음. 함량 때문에 약 저장을 막지 않기 위함임.
 *  - 글자는 NFKC로 정리한 품목명 기준임.
 * */
public record DrugStrength(String baseName, String text, BigDecimal micrograms, String unit) {

    public static final String NUMBER = "[0-9]+(?:\\.[0-9]+)?";
    private static final String MASS_UNIT = "마이크로그램|마이크로그람|밀리그램|밀리그람|그램|그람|mcg|μg|ug|mg|g";
    /** 질량 함량. REG-003 처방전 대조(PrescriptionDrugLookup)와 같은 표기를 씀. */
    public static final Pattern MASS = Pattern.compile("(" + NUMBER + "(?:[/+]" + NUMBER + ")*)"
            + "(" + MASS_UNIT + ")(?![a-z])", Pattern.CASE_INSENSITIVE);
    /** 농도·활성 단위처럼 값으로 비교하지 않는 표기. */
    private static final String VOLUME_UNIT = "밀리리터|리터|mL|L";
    public static final Pattern COMPLEX = Pattern.compile("(?:%|퍼센트|mEq|IU|국제단위|단위|/(?:" + NUMBER + ")?(?:" + VOLUME_UNIT + "|그램|그람|g))",
            Pattern.CASE_INSENSITIVE);
    // 숫자 중간(1,000mg의 000mg, .5mg의 5mg)에서 시작하지 않음. 읽지 못하는 숫자 표기는 함량 없음으로 둠.
    // 농도의 분모(/mL, /5밀리리터)까지 한 함량으로 읽어 분자만 질량으로 보지 않음.
    private static final Pattern TOKEN = Pattern.compile("(?<![0-9.,])" + NUMBER + "(?:\\s*[/+]\\s*" + NUMBER + ")*\\s*"
            + "(?:" + MASS_UNIT + "|%|퍼센트|mEq|IU|국제단위|단위|" + VOLUME_UNIT + ")"
            + "(?:\\s*/\\s*(?:" + NUMBER + ")?\\s*(?:" + VOLUME_UNIT + "|그램|그람|g))?(?![a-zA-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARENTHESES = Pattern.compile("\\([^)]*\\)?");

    private static final DrugStrength EMPTY = new DrugStrength(null, null, null, null);

    public static DrugStrength parse(String itemName) {
        if (itemName == null || itemName.isBlank()) return EMPTY;
        String name = Normalizer.normalize(itemName, Normalizer.Form.NFKC).strip();
        try {
            String outside = PARENTHESES.matcher(name).replaceAll(" ");
            Matcher token = TOKEN.matcher(outside);
            int start = -1, end = -1, count = 0;
            while (token.find()) {
                if (start < 0) start = token.start();
                end = token.end();
                count++;
            }
            if (count == 0) return new DrugStrength(base(outside, name), null, null, null);
            String text = outside.substring(start, end).strip();
            String base = base(outside.substring(0, start) + " " + outside.substring(end), name);
            // 함량이 둘 이상 적힌 품목은 복합 함량처럼 원문만 둠.
            BigDecimal value = count == 1 ? singleMass(text) : null;
            return new DrugStrength(base, text, value, value == null ? null : displayUnit(text));
        } catch (RuntimeException e) {
            return new DrugStrength(name, null, null, null);
        }
    }

    /** V3-REG-001·002: 검색·판독 이름의 함량(this)과 후보 함량을 비교함. */
    public StrengthMatch matchOf(DrugStrength candidate) {
        if (text == null || candidate == null || candidate.text == null) return StrengthMatch.UNKNOWN;
        if (micrograms != null && candidate.micrograms != null)
            return micrograms.compareTo(candidate.micrograms) == 0 ? StrengthMatch.MATCH : StrengthMatch.DIFFERENT;
        // 복합·농도 함량은 단위 표기만 맞춘 원문 글자가 같을 때만 일치로 봄.
        return canonical(text).equals(canonical(candidate.text)) ? StrengthMatch.MATCH : StrengthMatch.DIFFERENT;
    }

    private static String base(String withoutStrength, String fallback) {
        String base = withoutStrength.replaceAll("\\s+", " ").strip().replaceAll("[/+\\-\\s]+$", "");
        return base.isBlank() ? fallback : base;
    }

    private static BigDecimal singleMass(String text) {
        String compact = text.replaceAll("\\s+", "");
        Matcher mass = MASS.matcher(compact);
        if (!mass.matches() || mass.group(1).contains("/") || mass.group(1).contains("+")) return null;
        return new BigDecimal(mass.group(1)).multiply(factor(mass.group(2))).stripTrailingZeros();
    }

    private static BigDecimal factor(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "g", "그램", "그람" -> new BigDecimal("1000000");
            case "mg", "밀리그램", "밀리그람" -> new BigDecimal("1000");
            default -> BigDecimal.ONE;
        };
    }

    private static String displayUnit(String text) {
        Matcher mass = MASS.matcher(text.replaceAll("\\s+", ""));
        if (!mass.matches()) return null;
        return switch (mass.group(2).toLowerCase(Locale.ROOT)) {
            case "g", "그램", "그람" -> "g";
            case "mg", "밀리그램", "밀리그람" -> "mg";
            default -> "μg";
        };
    }

    private static String canonical(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .replace("마이크로그램", "mcg").replace("마이크로그람", "mcg")
                .replace("밀리그램", "mg").replace("밀리그람", "mg")
                .replace("그램", "g").replace("그람", "g")
                .replace("밀리리터", "ml").replace("리터", "l")
                .replace("μg", "mcg").replace("ug", "mcg");
    }
}
