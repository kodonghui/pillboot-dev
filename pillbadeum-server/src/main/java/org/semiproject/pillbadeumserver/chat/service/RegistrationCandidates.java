package org.semiproject.pillbadeumserver.chat.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.DrugStrength;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;
import org.semiproject.pillbadeumserver.image.service.PhotoCandidateLookup;
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * V3-CHAT-002: 등록 요청에서 뽑은 이름마다 V3-REG-001(함량 뗀 기본 이름으로 찾고 함량 일치 후보를 앞에 둠)로 공식 후보를 찾는다.
 * 찾기만 하고 저장하지 않는다. 공공데이터 장애는 빈 결과로 숨기지 않고 예외로 끝낸다.
 */
@Component
public class RegistrationCandidates {
    static final int PER_MENTION = 5;
    // '타이레놀 500'처럼 단위 없이 끝난 숫자는 밀리그램으로 읽는다(대화에서 흔한 표기, V3-CHAT-002 수용 기준).
    private static final Pattern BARE_NUMBER = Pattern.compile(".*\\D\\s*" + DrugStrength.NUMBER);

    /** 이름 하나의 후보. strength는 그 이름의 함량과 후보 품목명의 함량을 비교한 값(V3-REG-002)이다. */
    public record Candidate(String mention, PhotoCandidateData drug, StrengthMatch strength) {}

    private final PublicDataClient publicData;
    private final PillCatalog catalog;
    private final AsyncTaskExecutor executor;

    public RegistrationCandidates(PublicDataClient publicData, PillCatalog catalog,
                                  @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
        this.publicData = publicData;
        this.catalog = catalog;
        this.executor = executor;
    }

    /** 이름 순서대로 이름마다 후보 목록(최대 5개, 함량 일치·확인 불가·다름 순, 취하 품목은 뒤)을 돌려준다. 이름끼리는 함께 찾는다. */
    public List<List<Candidate>> find(List<String> mentions, ChatDeadline deadline) {
        var tasks = new ArrayList<Future<List<Candidate>>>();
        for (String mention : mentions) tasks.add(executor.submit(() -> forMention(mention, deadline)));
        try {
            var found = new ArrayList<List<Candidate>>();
            for (var task : tasks) found.add(deadline.await(task));
            return found;
        } finally {
            tasks.forEach(task -> task.cancel(true));
        }
    }

    List<Candidate> forMention(String mention, ChatDeadline deadline) {
        String keyword = searchName(mention);
        var found = search(mention, keyword, deadline);
        // '판콜 에이'처럼 띄어 쓴 이름은 붙여서 한 번 더 찾는다.
        String joined = keyword.replaceAll("\\s+", "");
        if (found.isEmpty() && !joined.equals(keyword)) found = search(mention, joined, deadline);
        return found;
    }

    private List<Candidate> search(String mention, String keyword, ChatDeadline deadline) {
        if (keyword.length() < DrugSearchService.MIN_NAME_LENGTH || keyword.length() > DrugSearchService.MAX_NAME_LENGTH)
            return List.of();
        var wanted = DrugStrength.parse(keyword);
        List<JsonNode> rows;
        if (wanted.text() != null && wanted.baseName() != null && wanted.baseName().length() >= DrugSearchService.MIN_NAME_LENGTH) {
            rows = rows(wanted.baseName(), deadline);
            if (rows.isEmpty()) rows = rows(keyword, deadline);   // 기본 이름 결과가 없으면 원래 이름으로
        } else {
            rows = rows(keyword, deadline);
        }
        var missingImages = new HashSet<String>();
        for (var row : rows) if (text(row, "ITEM_IMAGE", "ITEM_IMAGE_URL") == null && text(row, "ITEM_SEQ") != null)
            missingImages.add(text(row, "ITEM_SEQ"));
        Map<String, String> images = catalog.cachedImageUrls(missingImages);
        var unique = new LinkedHashMap<String, Candidate>();
        for (var row : rows) {
            PhotoCandidateData drug;
            // 필수 정보가 빠진 행 하나가 정상 후보를 없애지 않게 그 행만 뺀다.
            try { drug = PhotoCandidateLookup.permitCandidate(row, images.get(text(row, "ITEM_SEQ"))); }
            catch (ExternalApiException incomplete) { continue; }
            unique.putIfAbsent(drug.itemSeq(), new Candidate(mention, drug, wanted.matchOf(DrugStrength.parse(drug.name()))));
        }
        return unique.values().stream()
                .sorted(Comparator.comparingInt((Candidate c) -> rank(c.strength()))
                        .thenComparingInt(c -> Boolean.TRUE.equals(c.drug().cancelled()) ? 1 : 0))
                .limit(PER_MENTION).toList();
    }

    private List<JsonNode> rows(String query, ChatDeadline deadline) {
        var body = publicData.get(DrugSearchService.SEARCH_PATH, Map.of("item_name", query, "pageNo", 1,
                "numOfRows", DrugSearchService.STRENGTH_RANK_ROWS), deadline.remaining());
        return PublicDataClient.items(body);
    }

    /** 검색에 쓸 이름. 글자를 NFKC로 정리하고 공백을 하나로 줄이며, 단위 없이 끝난 숫자에는 mg을 붙인다. */
    static String searchName(String mention) {
        String name = Normalizer.normalize(mention, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        if (BARE_NUMBER.matcher(name).matches() && DrugStrength.parse(name).text() == null) return name + "mg";
        return name;
    }

    private static int rank(StrengthMatch strength) {
        return switch (strength) {
            case MATCH -> 0;
            case UNKNOWN -> 1;
            case DIFFERENT -> 2;
        };
    }

    private static String text(JsonNode row, String... fields) {
        for (String field : fields)
            for (var entry : row.properties())
                if (entry.getKey().equalsIgnoreCase(field) && !entry.getValue().isNull()) {
                    String value = entry.getValue().asString().strip();
                    if (!value.isEmpty()) return value;
                }
        return null;
    }
}
