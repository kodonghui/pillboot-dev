package org.semiproject.pillbadeumserver.image.service;

import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillIdentificationCandidate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Queries the MFDS APIs and ranks pill records using OCR/visual recognition output. */
@Service
public class PillDrugIdentificationService {

    private static final String PILL_IDENTIFICATION_PATH =
            "/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03";
    private static final String PRODUCT_PERMIT_PATH =
            "/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnInq08";
    private static final String EASY_DRUG_PATH =
            "/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList";
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final int MAX_ENRICHED_CANDIDATES = 5;

    private final org.semiproject.pillbadeumserver.common.external.PublicDataClient publicData;

    public PillDrugIdentificationService(org.semiproject.pillbadeumserver.common.external.PublicDataClient publicData) {
        this.publicData = publicData;
    }

    /**
     * Searches by available OCR/visual attributes, ranks the matches, then enriches
     * the best matches with permit and e약은요 information. Nothing is persisted.
     */
    public List<PillIdentificationCandidate> identify(PillIdentification input) {
        Objects.requireNonNull(input, "input");
        if (!hasSearchCriteria(input)) {
            throw new IllegalArgumentException("약 이름, 각인, 모양 또는 색상 중 하나 이상이 필요합니다.");
        }
        List<JsonNode> pillItems = new ArrayList<>();
        for (Map<String, Object> query : buildPillQueries(input)) {
            query.put("pageNo", 1);
            query.put("numOfRows", SEARCH_PAGE_SIZE);
            pillItems.addAll(items(get(PILL_IDENTIFICATION_PATH, query)));
        }

        List<RankedPill> ranked = pillItems.stream()
                .map(item -> new RankedPill(item, similarity(input, item)))
                .filter(candidate -> itemSeq(candidate.item) != null && candidate.score > 0)
                .collect(java.util.stream.Collectors.toMap(
                        candidate -> itemSeq(candidate.item),
                        candidate -> candidate,
                        (left, right) -> left.score >= right.score ? left : right,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparingDouble(RankedPill::score).reversed())
                .limit(MAX_ENRICHED_CANDIDATES)
                .toList();

        List<PillIdentificationCandidate> result = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            RankedPill match = ranked.get(index);
            String itemSeq = itemSeq(match.item);
            JsonNode permit = findByItemSeq(PRODUCT_PERMIT_PATH, "item_seq", "ITEM_SEQ", itemSeq);
            JsonNode easyDrug = findByItemSeq(EASY_DRUG_PATH, "itemSeq", "itemSeq", itemSeq);
            result.add(toCandidate(index + 1, match, permit, easyDrug));
        }
        return List.copyOf(result);
    }

    private JsonNode get(String path, Map<String, ?> parameters) {
        // 기존 무저장 경로도 공통 키·인코딩·호출 제한·오류 처리를 사용한다.
        return publicData.get(path, parameters);
    }

    private JsonNode findByItemSeq(String path, String queryName, String responseName, String itemSeq) {
        JsonNode body = get(path, Map.of(queryName, itemSeq, "pageNo", 1, "numOfRows", 10));
        return items(body).stream()
                .filter(item -> itemSeq.equals(text(item, responseName)))
                .findFirst()
                .orElse(null);
    }

    private static List<JsonNode> items(JsonNode body) {
        JsonNode node = body.path("items");
        if (node.has("item")) {
            node = node.path("item");
        }
        List<JsonNode> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(result::add);
        } else if (node.isObject()) {
            result.add(node);
        }
        return result;
    }

    private static List<Map<String, Object>> buildPillQueries(PillIdentification input) {
        List<Map<String, Object>> queries = new ArrayList<>();
        addQuery(queries, "print_front", input.printFront());
        addQuery(queries, "print_back", input.printBack());
        addQuery(queries, "item_name", input.itemName());
        if (queries.isEmpty()) {
            addQuery(queries, "drug_shape", input.shape());
            addQuery(queries, "color_class1", input.colorClass1());
            addQuery(queries, "color_class2", input.colorClass2());
            addQuery(queries, "line_front", input.lineFront());
            addQuery(queries, "line_back", input.lineBack());
            addQuery(queries, "entp_name", input.entpName());
        }
        return queries;
    }

    private static void addQuery(List<Map<String, Object>> queries, String key, String value) {
        if (present(value)) {
            queries.add(new LinkedHashMap<>(Map.of(key, value.strip())));
        }
    }

    private static boolean hasSearchCriteria(PillIdentification input) {
        return Stream.of(input.itemName(), input.entpName(), input.printFront(), input.printBack(),
                        input.shape(), input.colorClass1(), input.colorClass2(), input.lineFront(), input.lineBack())
                .anyMatch(PillDrugIdentificationService::present);
    }

    private static double similarity(PillIdentification input, JsonNode item) {
        Score score = new Score();
        score.add(input.itemName(), text(item, "ITEM_NAME"), 0.10);
        score.add(input.entpName(), text(item, "ENTP_NAME"), 0.05);
        score.add(input.printFront(), text(item, "PRINT_FRONT"), 0.25);
        score.add(input.printBack(), text(item, "PRINT_BACK"), 0.15);
        score.add(input.shape(), text(item, "DRUG_SHAPE"), 0.20);
        score.add(input.colorClass1(), text(item, "COLOR_CLASS1"), 0.10);
        score.add(input.colorClass2(), text(item, "COLOR_CLASS2"), 0.10);
        score.add(input.lineFront(), text(item, "LINE_FRONT"), 0.075);
        score.add(input.lineBack(), text(item, "LINE_BACK"), 0.075);
        return score.weight == 0 ? 0 : score.value / score.weight;
    }

    private static PillIdentificationCandidate toCandidate(int rank, RankedPill match,
                                                             JsonNode permit, JsonNode easyDrug) {
        JsonNode pill = match.item;
        return new PillIdentificationCandidate(
                rank,
                Math.round(match.score * 1000.0) / 10.0,
                itemSeq(pill),
                firstText(pill, "ITEM_NAME"),
                firstText(pill, "ENTP_NAME"),
                firstText(pill, "ITEM_IMAGE"),
                firstText(pill, "PRINT_FRONT"),
                firstText(pill, "PRINT_BACK"),
                firstText(pill, "DRUG_SHAPE"),
                firstText(pill, "COLOR_CLASS1"),
                firstText(pill, "COLOR_CLASS2"),
                firstText(pill, "LINE_FRONT"),
                firstText(pill, "LINE_BACK"),
                firstText(permit, "ETC_OTC_NAME", "SPCLTY_PBLC", "ETC_OTC_CODE"),
                firstText(permit, "CLASS_NAME"),
                firstText(permit, "ITEM_INGR_NAME", "MAIN_ITEM_INGR"),
                firstText(permit, "ITEM_PERMIT_DATE"),
                firstText(easyDrug, "efcyQesitm"),
                firstText(easyDrug, "useMethodQesitm"),
                firstText(easyDrug, "atpnWarnQesitm"),
                firstText(easyDrug, "atpnQesitm"),
                firstText(easyDrug, "intrcQesitm"),
                firstText(easyDrug, "seQesitm"),
                firstText(easyDrug, "depositMethodQesitm"));
    }

    private static String itemSeq(JsonNode item) {
        return firstText(item, "ITEM_SEQ", "itemSeq");
    }

    private static String firstText(JsonNode item, String... names) {
        if (item == null) {
            return null;
        }
        for (String name : names) {
            String value = text(item, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode item, String name) {
        if (item == null) {
            return null;
        }
        JsonNode value = item.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString("").strip();
        return text.isEmpty() ? null : text;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (present(value)) {
                return value.strip();
            }
        }
        return null;
    }

    private record RankedPill(JsonNode item, double score) {
    }

    private static final class Score {
        private double value;
        private double weight;

        private void add(String input, String actual, double fieldWeight) {
            if (!present(input)) {
                return;
            }
            weight += fieldWeight;
            value += fieldWeight * textSimilarity(input, actual);
        }
    }

    private static double textSimilarity(String input, String actual) {
        if (!present(actual)) {
            return 0;
        }
        String expected = normalize(input);
        String found = normalize(actual);
        if (expected.isEmpty() || found.isEmpty()) {
            return 0;
        }
        if (expected.equals(found)) {
            return 1;
        }
        if (expected.contains(found) || found.contains(expected)) {
            return 0.75;
        }
        int maxLength = Math.max(expected.length(), found.length());
        if (maxLength == 0) {
            return 0;
        }
        int distance = levenshtein(expected, found);
        double ratio = 1.0 - (double) distance / maxLength;
        return ratio >= 0.6 ? ratio : 0;
    }

    private static String normalize(String value) {
        return value.strip().toUpperCase().replaceAll("[\\s\\p{Punct}]+", "");
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
