package org.semiproject.pillbadeumserver.image.service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import tools.jackson.databind.JsonNode;

/** Both photo APIs use the same side-aware comparison of official identification records. */
public final class PillSimilarity {
    private PillSimilarity() {}

    public record Match(JsonNode item, double score, int imprintTier, int exactImprints, int exactFields) {}

    public static boolean hasCriteria(PillIdentification c) {
        return c != null && Stream.of(c.itemName(), c.entpName(), c.printFront(), c.printBack(),
                c.shape(), c.colorClass1(), c.colorClass2(), c.lineFront(), c.lineBack()).anyMatch(PillSimilarity::present);
    }

    public static boolean hasVisualCriteria(PillIdentification c) {
        return Stream.of(c.printFront(), c.printBack(), c.shape(), c.colorClass1(), c.colorClass2(),
                c.lineFront(), c.lineBack()).anyMatch(PillSimilarity::present);
    }

    public static List<Match> rank(PillIdentification c, List<JsonNode> rows) {
        if (!hasCriteria(c)) return List.of();
        var unique = new LinkedHashMap<String, Match>();
        for (JsonNode row : rows) {
            String code = text(row, "ITEM_SEQ");
            if (code == null || text(row, "ITEM_NAME") == null) continue;
            Match normal = compare(c, row, false), reversed = compare(c, row, true);
            Match best = ORDER.compare(normal, reversed) <= 0 ? normal : reversed;
            // With an imprint available, visual similarity alone is insufficient evidence.
            if (best.score() <= 0 || ((present(c.printFront()) || present(c.printBack())) && best.imprintTier() == 0)) continue;
            unique.merge(code, best, (a, b) -> ORDER.compare(a, b) <= 0 ? a : b);
        }
        return unique.values().stream().sorted(ORDER.thenComparing(m -> text(m.item(), "ITEM_SEQ"))).toList();
    }

    private static final Comparator<Match> ORDER = Comparator.comparingInt(Match::imprintTier).reversed()
            .thenComparing(Comparator.comparingDouble(Match::score).reversed())
            .thenComparing(Comparator.comparingInt(Match::exactImprints).reversed())
            .thenComparing(Comparator.comparingInt(Match::exactFields).reversed());

    private static Match compare(PillIdentification c, JsonNode row, boolean reverse) {
        String front = reverse ? "BACK" : "FRONT", back = reverse ? "FRONT" : "BACK";
        var score = new Score();
        double a = imprint(c.printFront(), text(row, "PRINT_" + front));
        double b = imprint(c.printBack(), text(row, "PRINT_" + back));
        int supplied = (present(c.printFront()) ? 1 : 0) + (present(c.printBack()) ? 1 : 0);
        int exact = (a == 1 ? 1 : 0) + (b == 1 ? 1 : 0);
        int close = (a >= .85 ? 1 : 0) + (b >= .85 ? 1 : 0);
        int tier = supplied == 0 ? 0 : exact == supplied ? 3 : close == supplied ? 2 : Math.max(a, b) >= .5 ? 1 : 0;
        score.add(c.printFront(), a, 4);
        score.add(c.printBack(), b, 4);
        score.add(c.itemName(), nameSimilarity(c.itemName(), text(row, "ITEM_NAME")), 2);
        score.add(c.entpName(), nameSimilarity(c.entpName(), text(row, "ENTP_NAME")), 1);
        score.add(c.shape(), equal(shape(c.shape()), shape(text(row, "DRUG_SHAPE"))), 1.5);
        Set<String> expectedColors = colors(c.colorClass1(), c.colorClass2());
        Set<String> actualColors = colors(text(row, "COLOR_CLASS1"), text(row, "COLOR_CLASS2"));
        for (String color : expectedColors) score.add(color, actualColors.contains(color) ? 1 : 0, 1);
        score.add(c.lineFront(), equal(line(c.lineFront()), line(text(row, "LINE_" + front))), .5);
        score.add(c.lineBack(), equal(line(c.lineBack()), line(text(row, "LINE_" + back))), .5);
        return new Match(row, score.ratio(), tier, exact, score.exact);
    }

    public static Boolean printsMatch(PillIdentification c, JsonNode row) {
        if (!present(c.printFront()) && !present(c.printBack())) return null;
        return pair(c, row, false) || pair(c, row, true);
    }

    private static boolean pair(PillIdentification c, JsonNode row, boolean reverse) {
        return (!present(c.printFront()) || imprint(c.printFront(), text(row, reverse ? "PRINT_BACK" : "PRINT_FRONT")) == 1)
                && (!present(c.printBack()) || imprint(c.printBack(), text(row, reverse ? "PRINT_FRONT" : "PRINT_BACK")) == 1);
    }

    private static double imprint(String expected, String actual) {
        if (!present(expected) || !present(actual)) return 0;
        String a = normalize(expected), b = normalize(actual);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (ocr(a).equals(ocr(b))) return .9;
        // Short dose numbers such as 10 and 40 must not be treated as similar.
        if (a.matches("[0-9]+") && b.matches("[0-9]+")) return 0;
        if (Math.min(a.length(), b.length()) >= 2 && (a.contains(b) || b.contains(a)))
            return Math.min(.8, (double)Math.min(a.length(), b.length()) / Math.max(a.length(), b.length()));
        if (Math.min(a.length(), b.length()) < 3) return 0;
        double ratio = editRatio(a, b);
        return ratio >= .66 ? ratio * .85 : 0;
    }

    private static double nameSimilarity(String expected, String actual) {
        if (!present(expected) || !present(actual)) return 0;
        String a = normalize(expected).replace("밀리그램", "MG"), b = normalize(actual).replace("밀리그램", "MG");
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (a.contains(b) || b.contains(a)) return .8;
        double ratio = editRatio(a, b);
        return ratio >= .6 ? ratio : 0;
    }

    private static Set<String> colors(String... values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) if (present(value)) for (String part : value.split("[,/|·\\s]+")) {
            String normalized = normalize(part);
            normalized = switch (normalized) {
                case "하양", "하얀색", "흰색", "백색", "WHITE" -> "WHITE";
                case "노랑", "노란색", "황색", "YELLOW" -> "YELLOW";
                case "분홍", "분홍색", "PINK" -> "PINK";
                case "파랑", "파란색", "청색", "BLUE" -> "BLUE";
                case "주황", "주황색", "ORANGE" -> "ORANGE";
                case "빨강", "빨간색", "적색", "RED" -> "RED";
                case "초록", "초록색", "녹색", "GREEN" -> "GREEN";
                default -> normalized;
            };
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    private static String shape(String value) {
        if (!present(value)) return "";
        return switch (normalize(value)) {
            case "원형", "둥근형", "ROUND", "CIRCLE" -> "ROUND";
            case "타원형", "타원", "OVAL", "ELLIPSE" -> "OVAL";
            case "장방형", "직사각형", "OBLONG", "RECTANGLE" -> "OBLONG";
            default -> normalize(value);
        };
    }

    private static String line(String value) {
        if (!present(value)) return "";
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "-", "일자", "일자형", "MINUS" -> "MINUS";
            case "+", "십자", "십자형", "PLUS" -> "PLUS";
            case "없음", "없다", "NONE", "NO" -> "NONE";
            default -> normalize(value);
        };
    }

    private static double equal(String a, String b) { return !a.isEmpty() && a.equals(b) ? 1 : 0; }
    private static String ocr(String value) { return value.replace('O', '0').replace('I', '1').replace('L', '1'); }
    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    public static boolean present(String value) {
        return value != null && !value.isBlank() && !Set.of("NULL", "UNDEFINED", "UNKNOWN", "미확인", "모름").contains(value.strip().toUpperCase(Locale.ROOT));
    }
    public static String text(JsonNode row, String field) {
        if (row == null) return null;
        for (var property : row.properties()) if (property.getKey().equalsIgnoreCase(field)) {
            String value = property.getValue().asString("").strip();
            return present(value) ? value : null;
        }
        return null;
    }
    private static double editRatio(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1]; current[0] = i;
            for (int j = 1; j <= b.length(); j++) current[j] = Math.min(Math.min(previous[j] + 1, current[j-1] + 1), previous[j-1] + (a.charAt(i-1) == b.charAt(j-1) ? 0 : 1));
            previous = current;
        }
        return 1 - (double)previous[b.length()] / Math.max(a.length(), b.length());
    }
    private static final class Score {
        double value, weight; int exact;
        void add(String input, double similarity, double fieldWeight) {
            if (!present(input)) return;
            weight += fieldWeight; value += similarity * fieldWeight;
            if (similarity == 1) exact++;
        }
        double ratio() { return weight == 0 ? 0 : value / weight; }
    }
}
