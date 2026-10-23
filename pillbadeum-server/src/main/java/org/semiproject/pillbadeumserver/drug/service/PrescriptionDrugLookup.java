package org.semiproject.pillbadeumserver.drug.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import tools.jackson.databind.JsonNode;

/** REG-003: 판독 원문을 보존하며 조회 표기만 바꾸고, 제품 함량·제형이 다른 후보를 제외함. */
public final class PrescriptionDrugLookup {
    private static final String NUMBER = "[0-9]+(?:\\.[0-9]+)?";
    private static final Pattern MASS = Pattern.compile("(" + NUMBER + "(?:[/+]" + NUMBER + ")*)"
            + "(마이크로그램|마이크로그람|밀리그램|밀리그람|그램|그람|mcg|μg|ug|mg|g)(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADMINISTRATION = Pattern.compile("/" + NUMBER + "(?:정|캡슐|캅셀|포|개)$");
    private static final Pattern COMPLEX = Pattern.compile("(?:%|퍼센트|mEq|IU|국제단위|단위|/(?:" + NUMBER + ")?(?:mL|L|그램|그람|g))", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM = Pattern.compile("(캡슐|캅셀|정|시럽|과립|액|산)$");
    private final PublicDataClient client;

    public PrescriptionDrugLookup(PublicDataClient client) { this.client = client; }

    /** 최대 세 가지 조회만 수행함. 통신 장애를 빈 결과로 숨기거나 함량을 버리고 채택하지 않음. */
    public List<JsonNode> find(String name, String maker) {
        var clue = new Clue(name);
        for (String query : clue.queries()) {
            var params = new LinkedHashMap<String, Object>();
            params.put("item_name", query);
            if (maker != null && !maker.isBlank()) params.put("entp_name", maker.strip());
            params.put("pageNo", 1);
            params.put("numOfRows", 100);
            var matches = PublicDataClient.items(client.get(DrugSyncService.PERMIT_INFO_PATH, params)).stream()
                    .filter(row -> clue.matches(text(row, "ITEM_NAME"))).toList();
            if (!matches.isEmpty()) return matches;
        }
        return List.of();
    }

    private static final class Clue {
        final String clean, base, form;
        final List<BigDecimal> strengths;
        final boolean complex, truncated, queryable;

        Clue(String name) {
            String original = clean(name);
            String withoutEllipsis = original.replaceFirst("\\.{2,}$", "");
            int open = withoutEllipsis.lastIndexOf('(');
            boolean openDescription = open >= 0 && withoutEllipsis.indexOf(')', open) < 0;
            truncated = !original.equals(withoutEllipsis) || openDescription;
            clean = openDescription ? withoutEllipsis.substring(0, open) : withoutEllipsis;
            strengths = strengths(clean);
            complex = COMPLEX.matcher(clean).find();
            base = base(clean);
            form = form(base);
            // 잘린 함량·제형은 추측하지 않음. 온전한 제품명 또는 질량 단위까지 읽힌 경우만 조회함.
            var mass = MASS.matcher(clean);
            boolean completeMass = false;
            while (mass.find()) completeMass = mass.end() == clean.length();
            queryable = !truncated || completeMass || FORM.matcher(clean).find();
        }

        List<String> queries() {
            if (!queryable) return List.of();
            var result = new LinkedHashSet<String>();
            if (!clean.isBlank()) result.add(clean);
            result.add(swapUnits(clean));
            // 농도·활성 단위처럼 이 비교기가 해석하지 않는 표기는 이름만으로 넓히지 않음.
            if (!complex && !strengths.isEmpty() && base.length() >= 2) result.add(base);
            result.removeIf(String::isBlank);
            return List.copyOf(result);
        }

        boolean matches(String candidateName) {
            if (candidateName == null || candidateName.isBlank()) return false;
            var candidate = clean(candidateName);
            if (complex) {
                String expected = canonicalUnits(clean), actual = canonicalUnits(candidate);
                return actual.equals(expected) || actual.startsWith(expected + "(");
            }
            if (!strengths.isEmpty() || truncated) {
                if (!strengths.isEmpty() && (COMPLEX.matcher(candidate).find() || !strengths.equals(strengths(candidate)))) return false;
                // 넓은 이름 조회의 결과라도 다른 제품을 채택하지 않음. 괄호의 성분 설명은 제품명과 구분함.
                if (!base(candidate).equals(base)) return false;
            }
            if (form != null) {
                String candidateBase = base(candidate);
                if (!form.equals(form(candidateBase))) return false;
                if (base.contains("서방") != candidateBase.contains("서방")) return false;
                if (base.contains("장용") != candidateBase.contains("장용")) return false;
            }
            return true;
        }
    }

    /** /1정은 투여 단위이며, 그 앞의 60mg은 제품 함량이므로 지우지 않음. */
    private static String clean(String value) {
        String normalized = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").strip();
        return ADMINISTRATION.matcher(normalized).replaceFirst("");
    }

    private static String base(String value) {
        String withoutStrength = MASS.matcher(value).replaceAll("");
        return withoutStrength.replaceAll("\\([^)]*\\)", "").replaceAll("[/+]+$", "")
                .replace("캅셀", "캡슐").toUpperCase(Locale.ROOT);
    }

    private static String form(String base) {
        var match = FORM.matcher(base);
        return match.find() ? match.group(1).replace("캅셀", "캡슐") : null;
    }

    /** 질량은 마이크로그램의 정확한 십진수로 비교함. 복합 함량의 순서를 바꾸지 않음. */
    private static List<BigDecimal> strengths(String value) {
        var result = new ArrayList<BigDecimal>();
        var match = MASS.matcher(value);
        while (match.find()) {
            String unit = match.group(2).toLowerCase(Locale.ROOT);
            BigDecimal factor = switch (unit) {
                case "g", "그램", "그람" -> new BigDecimal("1000000");
                case "mg", "밀리그램", "밀리그람" -> new BigDecimal("1000");
                default -> BigDecimal.ONE;
            };
            for (String number : match.group(1).split("[/+]"))
                result.add(new BigDecimal(number).multiply(factor).stripTrailingZeros());
        }
        return List.copyOf(result);
    }

    private static String swapUnits(String value) {
        var match = MASS.matcher(value);
        return match.replaceAll(m -> m.group(1) + switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "mg" -> "밀리그램";
            case "밀리그램", "밀리그람" -> "mg";
            case "g" -> "그램";
            case "그램", "그람" -> "g";
            case "마이크로그램", "마이크로그람" -> "mcg";
            default -> "마이크로그램";
        });
    }

    private static String canonicalUnits(String value) {
        return value.replace("마이크로그램", "mcg").replace("마이크로그람", "mcg")
                .replace("밀리그램", "mg").replace("밀리그람", "mg")
                .replace("그램", "g").replace("그람", "g").toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode row, String field) {
        for (var property : row.properties())
            if (property.getKey().equalsIgnoreCase(field) && !property.getValue().isNull())
                return property.getValue().asString("");
        return null;
    }
}
