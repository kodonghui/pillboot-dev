package org.semiproject.pillbadeumserver.drug.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import tools.jackson.databind.JsonNode;

/**
 * 2026-11-08 카톡 기록('유코스텐스캡슐20g'·'뮤코스텐캡슐 200미리'를 못 찾고 '비슷한거찾아봐'에도 답하지 못함): 이름이 꼭 맞는
 * 품목이 없을 때 비슷한 이름의 공식 품목을 최대 3개 찾는다. 먼저 메모리에 있는 낱알 목록(정·캡슐)을 자모 유사도로 훑고,
 * 모자라면 이름 가운데 세 글자 조각으로 허가정보를 최대 세 번 찾는다. 비슷한 후보는 함량 '확인 불가'로 두어 미리 고르지 않는다.
 */
public final class SimilarDrugNames {
    public static final int LIMIT = 3;
    private static final int MAX_QUERIES = 3;
    private static final String FORM = "(?:연질캡슐|경질캡슐|캡슐|캅셀|정제|정|시럽|현탁액|액|산|과립|겔|크림|연고)$";
    private final PublicDataClient client;
    private final Supplier<List<JsonNode>> catalog;

    /** @param catalog 준비된 낱알 목록 행(기다리지 않음. 준비 전이면 빈 목록) */
    public SimilarDrugNames(PublicDataClient client, Supplier<List<JsonNode>> catalog) {
        this.client = client;
        this.catalog = catalog;
    }

    /** 비슷한 순서의 공식 행(최대 3개, 같은 품목코드는 한 번). 통신 장애는 그대로 던진다. */
    public List<JsonNode> find(String name, Duration remaining) {
        String key = DrugNameSimilarity.key(name);
        if (key.length() < 2) return List.of();
        var scored = new LinkedHashMap<String, Scored>();
        for (var row : catalog.get()) consider(scored, name, row);
        if (best(scored).size() < LIMIT) {
            long endsAt = remaining == null ? Long.MAX_VALUE : System.nanoTime() + remaining.toNanos();
            for (String query : queries(key)) {
                long left = endsAt - System.nanoTime();
                if (left <= 0) break;
                var params = Map.<String, Object>of("item_name", query, "pageNo", 1, "numOfRows", 100);
                var body = remaining == null ? client.get(DrugSyncService.PERMIT_INFO_PATH, params)
                        : client.get(DrugSyncService.PERMIT_INFO_PATH, params, Duration.ofNanos(left));
                for (var row : PublicDataClient.items(body)) consider(scored, name, row);
                if (best(scored).size() >= LIMIT) break;
            }
        }
        return best(scored).stream().map(Scored::row).toList();
    }

    /** 이름 가운데 조각. 제형을 뗀 앞부분에서 세 글자씩(없으면 두 글자) 뒤쪽부터 고른다('유코스텐스' → 코스텐·스텐스·유코스). */
    static List<String> queries(String key) {
        String core = key.replaceFirst(FORM, "");
        if (core.length() < 2) core = key;
        var result = new LinkedHashSet<String>();
        int size = core.length() >= 4 ? 3 : 2;
        // 첫 글자를 잘못 읽은 경우(유↔뮤)가 흔해 둘째 글자부터의 조각을 먼저 본다.
        for (int start = 1; start + size <= core.length(); start++) result.add(core.substring(start, start + size));
        result.add(core.substring(0, Math.min(size, core.length())));
        return result.stream().filter(query -> query.length() >= 2).limit(MAX_QUERIES).toList();
    }

    private static void consider(Map<String, Scored> scored, String name, JsonNode row) {
        String code = text(row, "ITEM_SEQ"), official = text(row, "ITEM_NAME");
        if (code == null || official == null) return;
        double score = DrugNameSimilarity.similarity(name, official);
        if (score < DrugNameSimilarity.SIMILAR) return;
        var previous = scored.get(code);
        if (previous == null || previous.score() < score) scored.put(code, new Scored(row, score));
    }

    private static List<Scored> best(Map<String, Scored> scored) {
        var values = new ArrayList<>(scored.values());
        values.sort(Comparator.comparingDouble(Scored::score).reversed());
        return values.stream().limit(LIMIT).toList();
    }

    private record Scored(JsonNode row, double score) {}

    private static String text(JsonNode row, String field) {
        for (var property : row.properties())
            if (property.getKey().equalsIgnoreCase(field) && !property.getValue().isNull()) {
                String value = property.getValue().asString("").strip();
                return value.isEmpty() ? null : value;
            }
        return null;
    }
}
