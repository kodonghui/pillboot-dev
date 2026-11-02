package org.semiproject.pillbadeumserver.drug.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.DrugStrength;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import tools.jackson.databind.JsonNode;

/**
 * REG-003: 판독 원문을 보존하며 조회 표기만 바꾸고, 제품 함량·제형이 다른 후보를 제외함(find, 채팅이 씀).
 * V3-REG-002: 처방전 사진 후보는 함량만 다른 같은 제품을 빼지 않고 '함량 다름'으로 남김(findWithStrength).
 */
public final class PrescriptionDrugLookup {
    // 함량 표기는 V3-DRUG-001 함량 분리(DrugStrength)와 같은 규칙을 씀.
    private static final String NUMBER = DrugStrength.NUMBER;
    private static final Pattern MASS = DrugStrength.MASS;
    private static final Pattern ADMINISTRATION = Pattern.compile("/" + NUMBER + "(?:정|캡슐|캅셀|포|개)$");
    private static final Pattern COMPLEX = DrugStrength.COMPLEX;
    private static final Pattern FORM = Pattern.compile("(캡슐|캅셀|정|시럽|과립|액|산)$");
    private static final Pattern PARTIAL_STRENGTH = Pattern.compile(".+(?:캡슐|캅셀|정|시럽|과립|액|산)"
            + NUMBER + "(?:[/+]" + NUMBER + ")*");
    private final PublicDataClient client;

    public PrescriptionDrugLookup(PublicDataClient client) { this.client = client; }

    /** 최대 세 가지 조회만 수행함. 통신 장애를 빈 결과로 숨기거나 함량을 버리고 채택하지 않음. */
    public List<JsonNode> find(String name, String maker) {
        return find(name, maker, null).rows();
    }

    /** V3-REG-002: 처방전 후보 한 행과 판독 이름에 대한 함량 상태. */
    public record Candidate(JsonNode row, StrengthMatch strength) {}

    /**
     * V3-REG-002(REG-003 변경): 판독 이름에 질량 함량이 있으면 함량 뗀 기본 이름으로 먼저 조회해, 기본 이름(제형 · 서방 · 장용 포함)이
     * 같고 함량만 다른 품목도 '함량 다름'으로 남김. 기본 이름 결과에 일치 품목이 없거나 결과가 잘렸으면 원래 표기로 일치 품목을 더함.
     * 농도 · 활성 단위 · 잘린 이름 · 함량 없는 이름은 기존 대조(find)와 같은 후보를 돌려줌. 순서는 호출자가 정함.
     */
    public List<Candidate> findWithStrength(String name, String maker) {
        var clue = new Clue(name);
        if (!clue.widens()) {
            var strength = clue.confirmed() ? StrengthMatch.MATCH : StrengthMatch.UNKNOWN;
            return find(name, maker).stream().map(row -> new Candidate(row, strength)).toList();
        }
        var body = client.get(DrugSyncService.PERMIT_INFO_PATH, params(clue.bare, maker));
        var items = PublicDataClient.items(body);
        var found = new ArrayList<Candidate>();
        for (var row : items) {
            var strength = clue.strengthOf(text(row, "ITEM_NAME"));
            if (strength != null) found.add(new Candidate(row, strength));
        }
        // 일치 품목이 없으면 원래 표기로 찾음('합성20mg정'처럼 함량을 떼면 이어지지 않는 이름). 잘린 결과는 첫 페이지에
        // 일치 품목이 하나 보여도 뒤쪽에 더 있을 수 있어, 자동 선택이 유일하다고 보지 않게 원래 표기로 일치 품목을 다시 모음.
        boolean matched = found.stream().anyMatch(candidate -> candidate.strength() == StrengthMatch.MATCH);
        if (!matched || PublicDataClient.totalCount(body) > items.size()) {
            var seen = new HashSet<String>();
            found.forEach(candidate -> seen.add(text(candidate.row(), "ITEM_SEQ")));
            for (String query : clue.exactQueries()) {
                var exact = PublicDataClient.items(client.get(DrugSyncService.PERMIT_INFO_PATH, params(query, maker))).stream()
                        .filter(row -> clue.matches(text(row, "ITEM_NAME"))).toList();
                if (exact.isEmpty()) continue;
                exact.stream().filter(row -> seen.add(text(row, "ITEM_SEQ")))
                        .forEach(row -> found.add(new Candidate(row, StrengthMatch.MATCH)));
                break;
            }
        }
        return List.copyOf(found);
    }

    /** V3-REG-002: 저장된 판독 이름과 후보 품목명의 함량 상태. 후보를 다시 보여 줄 때도 조회와 같은 대조를 씀. */
    public static StrengthMatch strengthMatch(String readName, String candidateName) {
        var strength = new Clue(readName).strengthOf(candidateName);
        return strength == null ? StrengthMatch.UNKNOWN : strength;
    }

    /** 채팅은 잘린 첫 페이지의 한 품목을 유일한 제품으로 확정하지 않음. 기존 처방전 필터를 재사용함. */
    public Matches find(String name, String maker, Duration remaining) {
        long endsAt = remaining == null ? Long.MAX_VALUE : System.nanoTime() + remaining.toNanos();
        var clue = new Clue(name);
        for (String query : clue.queries()) {
            var params = params(query, maker);
            var body = remaining == null ? client.get(DrugSyncService.PERMIT_INFO_PATH, params)
                    : client.get(DrugSyncService.PERMIT_INFO_PATH, params, Duration.ofNanos(Math.max(1, endsAt - System.nanoTime())));
            var items = PublicDataClient.items(body);
            var matches = items.stream()
                    .filter(row -> clue.matches(text(row, "ITEM_NAME"))
                            || remaining != null && clue.matchesPartialName(text(row,"ITEM_NAME"))).toList();
            boolean more = remaining != null && PublicDataClient.totalCount(body) > items.size();
            if (!matches.isEmpty() || more) return new Matches(matches, more);
        }
        return new Matches(List.of(), false);
    }

    public record Matches(List<JsonNode> rows, boolean more) {}

    private static final class Clue {
        final String clean, bare, base, form;
        final List<BigDecimal> strengths;
        final boolean complex, truncated, partialStrength, queryable;

        Clue(String name) {
            String original = clean(name);
            String withoutEllipsis = original.replaceFirst("(?:\\.{2,}|[⋯…‥])+$", "");
            int open = withoutEllipsis.lastIndexOf('(');
            boolean openDescription = open >= 0 && withoutEllipsis.indexOf(')', open) < 0;
            truncated = !original.equals(withoutEllipsis) || openDescription;
            clean = openDescription ? withoutEllipsis.substring(0, open) : withoutEllipsis;
            strengths = strengths(clean);
            complex = COMPLEX.matcher(clean).find();
            bare = bare(clean);
            base = base(clean);
            form = form(base);
            // 판독 원문은 호출자가 보존한다. 잘린 숫자는 완성하지 않고 읽힌 접두어 그대로 후보만 조회한다.
            partialStrength = truncated && strengths.isEmpty() && PARTIAL_STRENGTH.matcher(clean).matches();
            var mass = MASS.matcher(clean);
            boolean completeMass = false;
            while (mass.find()) completeMass = mass.end() == clean.length();
            queryable = !truncated || completeMass || FORM.matcher(clean).find() || partialStrength;
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

        /** 원래 표기와 단위만 바꾼 표기. 기본 이름 조회는 빼고 순서를 지킴. */
        List<String> exactQueries() {
            var result = new LinkedHashSet<>(List.of(clean, swapUnits(clean)));
            result.removeIf(String::isBlank);
            return List.copyOf(result);
        }

        /** 질량 함량이 온전히 읽혀 함량 뗀 이름으로 넓혀 볼 수 있는 판독 이름인지. */
        boolean widens() {
            return queryable && !truncated && !complex && !strengths.isEmpty() && bare.length() >= 2;
        }

        /** 판독 이름의 함량을 확인할 수 있는지. 함량이 없거나 숫자가 잘렸으면 확인 불가임. */
        boolean confirmed() {
            return !partialStrength && (complex || !strengths.isEmpty());
        }

        /** 같은 제품이면 함량 상태를, 다른 제품이면 null을 돌려줌. 함량만 다른 품목은 넓혀 보는 이름에서만 남김. */
        StrengthMatch strengthOf(String candidateName) {
            if (candidateName == null || candidateName.isBlank()) return null;
            if (matches(candidateName)) return confirmed() ? StrengthMatch.MATCH : StrengthMatch.UNKNOWN;
            if (!widens()) return null;
            var candidate = clean(candidateName);
            // 기본 이름에 제형 · 서방 · 장용이 들어 있으므로 기본 이름이 같으면 제형도 같음.
            if (COMPLEX.matcher(candidate).find() || !base(candidate).equals(base)) return null;
            return strengths(candidate).isEmpty() ? StrengthMatch.UNKNOWN : StrengthMatch.DIFFERENT;
        }

        boolean matches(String candidateName) {
            if (candidateName == null || candidateName.isBlank()) return false;
            var candidate = clean(candidateName);
            if (partialStrength)
                return !COMPLEX.matcher(candidate).find()
                        && canonicalUnits(candidate).startsWith(canonicalUnits(clean));
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

        boolean matchesPartialName(String candidate) {
            return candidate != null && form == null && !complex && !strengths.isEmpty()
                    && base(clean(candidate)).startsWith(base) && strengths.equals(strengths(clean(candidate)))
                    && !COMPLEX.matcher(clean(candidate)).find();
        }
    }

    /** /1정은 투여 단위이며, 그 앞의 60mg은 제품 함량이므로 지우지 않음. */
    private static String clean(String value) {
        String normalized = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").strip();
        return ADMINISTRATION.matcher(normalized).replaceFirst("");
    }

    private static String base(String value) {
        return bare(value).replace("캅셀", "캡슐").toUpperCase(Locale.ROOT);
    }

    /**
     * 함량과 괄호 설명만 뗀 이름. 조회어로 쓰므로 표기(캅셀 · 대소문자)는 바꾸지 않음.
     * 후보 이름은 50자로 잘려 저장되므로 닫히지 않은 끝 괄호도 성분 설명으로 봄(다시 보기에서도 같은 대조).
     */
    private static String bare(String value) {
        String withoutStrength = MASS.matcher(value).replaceAll("");
        return withoutStrength.replaceAll("\\([^)]*\\)", "").replaceAll("\\([^)]*$", "").replaceAll("[/+]+$", "");
    }

    private static Map<String, Object> params(String query, String maker) {
        var params = new LinkedHashMap<String, Object>();
        params.put("item_name", query);
        if (maker != null && !maker.isBlank()) params.put("entp_name", maker.strip());
        params.put("pageNo", 1);
        params.put("numOfRows", 100);
        return params;
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
                .replace("그램", "g").replace("그람", "g")
                .replace("밀리리터", "mL").replace("리터", "L").toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode row, String field) {
        for (var property : row.properties())
            if (property.getKey().equalsIgnoreCase(field) && !property.getValue().isNull())
                return property.getValue().asString("");
        return null;
    }
}
