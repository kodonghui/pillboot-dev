package org.semiproject.pillbadeumserver.drug.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import org.semiproject.pillbadeumserver.drug.domain.DrugStrength;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;

/**
 * 2026-11-08 카톡 기록(사진 '룩펠정'을 '록펠정'으로 읽음, '뮤코스텐캡슐'을 '유코스텐스캡슐'로 씀): 약 이름을 자모로 나눠
 * 얼마나 비슷한지 잰다. 비슷한 이름은 후보로 보여 주기만 하고 미리 고르지 않는다(사람이 고름).
 * 함량·괄호 설명·띄어쓰기는 빼고 제품 이름 부분만 비교한다.
 */
public final class DrugNameSimilarity {
    /** 이 값 이상이면 '비슷한 이름' 후보로 보여 준다. 한 글자 모음만 다른 3글자 이름(록펠정·룩펠정)은 약 0.89다. */
    public static final double SIMILAR = 0.6;

    private DrugNameSimilarity() {}

    /** 비교에 쓰는 이름: NFKC, 공백·괄호 설명·함량을 빼고 대문자. 캅셀은 캡슐로 본다. */
    public static String key(String name) {
        String value = Normalizer.normalize(Objects.toString(name, ""), Normalizer.Form.NFKC).replaceAll("\\s+", "");
        value = value.replaceAll("\\([^)]*\\)", "").replaceAll("\\([^)]*$", "");
        value = DrugStrength.MASS.matcher(value).replaceAll("");
        value = value.replaceAll("[0-9.,/+]*(?:mL|ml|ML|밀리리터|리터|L|미리|g|mg|mcg|㎎|㎖)?$", "");
        return value.replace("캅셀", "캡슐").replaceAll("[^\\p{L}\\p{N}]", "").toUpperCase(Locale.ROOT);
    }

    /** 0~1. 자모 편집 거리로 잰다. 둘 중 하나가 비면 0. */
    public static double similarity(String left, String right) {
        String a = jamo(key(left)), b = jamo(key(right));
        if (a.isEmpty() || b.isEmpty()) return 0;
        int distance = distance(a, b);
        return 1.0 - (double) distance / Math.max(a.length(), b.length());
    }

    /**
     * 보험코드로 찾은 품목이 읽은 이름과 같은 약이라고 볼 수 있는지. 두 이름에 적힌 함량이 서로 다르면 같은 약이 아니다
     * ('가상룩펠정5mg'와 '가상룩펠정10mg', PR #77 Codex 리뷰: 이름 비교는 함량을 빼고 해서 이 경우도 맞다고 봤음).
     * 그 밖에 읽은 이름이 없으면(코드만 읽음) 볼 것이 없어 true고, 한쪽 이름이 다른 쪽을 품거나 자모 유사도가 기준
     * 이상이면 같은 약으로 본다.
     */
    public static boolean consistent(String readName, String officialName) {
        if (strengthConflict(readName, officialName)) return false;
        String read = key(readName), official = key(officialName);
        if (read.isEmpty()) return true;
        if (official.isEmpty()) return false;
        if (official.contains(read) || read.contains(official)) return true;
        return similarity(readName, officialName) >= SIMILAR;
    }

    /** 두 이름에 모두 함량이 적혀 있고 서로 다른지. 한쪽이라도 함량이 없으면 false다(DrugStrength.matchOf와 같은 대조). */
    public static boolean strengthConflict(String readName, String officialName) {
        return DrugStrength.parse(readName).matchOf(DrugStrength.parse(officialName)) == StrengthMatch.DIFFERENT;
    }

    /** 한글 음절을 초성·중성·종성으로 나눈다. 다른 글자는 그대로 둔다. */
    static String jamo(String value) {
        var out = new StringBuilder(value.length() * 3);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int index = c - 0xAC00;
                out.append((char) (0x1100 + index / 588));
                out.append((char) (0x1161 + (index % 588) / 28));
                int tail = index % 28;
                if (tail > 0) out.append((char) (0x11A7 + tail));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }
}
