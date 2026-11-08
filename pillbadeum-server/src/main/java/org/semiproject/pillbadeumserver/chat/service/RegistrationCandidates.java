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
import org.semiproject.pillbadeumserver.drug.service.DrugNameSimilarity;
import org.semiproject.pillbadeumserver.drug.service.DrugSearchService;
import org.semiproject.pillbadeumserver.drug.service.InsuranceCodeLookup;
import org.semiproject.pillbadeumserver.drug.service.SimilarDrugNames;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;
import org.semiproject.pillbadeumserver.image.domain.PrescriptionDose;
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

    // '200미리'처럼 단위를 줄여 말한 숫자. 정·캡슐이면 mg, 시럽·액이면 mL로 읽는다(2026-11-08 카톡 기록: '뮤코스텐캡슐 200미리').
    private static final Pattern SHORT_UNIT = Pattern.compile("(" + DrugStrength.NUMBER + ")\\s*(?:미리|밀리)(?!그램|그람|리터|미터)");
    private static final Pattern LIQUID = Pattern.compile("시럽|액|현탁|드롭|ml|mL|ML");
    // 처방전 1회 투약량이 mL인 물약 품목: 괄호·끝의 함량을 뺀 이름이 '시럽'으로 끝남. 포로 주는 현탁액, 방울로 넣는 점안액,
    // 가루로도 주는 건조시럽은 단위를 알 수 없어 넣지 않는다(개수 그대로 보임)
    private static final Pattern LIQUID_PRODUCT = Pattern.compile(".*(?<!건조)시럽");

    /** 2026-11-08: 시럽 품목명인지(처방전 1회 투약량의 단위를 mL로 봄). */
    static boolean liquidProduct(String name) {
        if (name == null) return false;
        String plain = name.replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", "")
                .replaceAll("[0-9.,/]+(?:mg|mL|ml|g|밀리그램|밀리그람|밀리리터|그램|%)?$", "");
        return LIQUID_PRODUCT.matcher(plain).matches();
    }

    /**
     * 이름 하나의 후보. strength는 그 이름의 함량과 후보 품목명의 함량을 비교한 값(V3-REG-002)이다.
     * similar는 이름이 꼭 맞는 품목이 없어 비슷한 이름으로 찾은 후보(미리 고르지 않음), prescription은 처방전 사진에서 읽은
     * 그 줄의 1회 투약량·1일 횟수·총 일수다(2026-11-08). 둘 다 없으면 false·null.
     */
    public record Candidate(String mention, PhotoCandidateData drug, StrengthMatch strength, boolean similar,
                            PrescriptionDose prescription) {
        public Candidate(String mention, PhotoCandidateData drug, StrengthMatch strength) {
            this(mention, drug, strength, false, null);
        }
    }

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

    /**
     * 2026-11-08: 이름에 9자리 보험코드가 있으면 그 코드로 먼저 찾는다('646001150 유코스텐캡슐 200mg'). 이름으로 찾지 못하면
     * 비슷한 이름의 후보(최대 3개, 함량 '확인 불가')를 돌려준다. 공공데이터 장애는 예외로 끝낸다.
     */
    List<Candidate> forMention(String mention, ChatDeadline deadline) {
        String code = InsuranceCodeLookup.extract(mention);
        String named = code == null ? mention : InsuranceCodeLookup.withoutCode(mention);
        if (code != null) {
            var rows = new InsuranceCodeLookup(publicData).find(code, deadline.remaining());
            if (!rows.isEmpty()) return byCode(mention, named, rows);
            if (named.length() < DrugSearchService.MIN_NAME_LENGTH) return List.of();
        }
        String keyword = searchName(named);
        var found = search(mention, keyword, deadline);
        // '판콜 에이'처럼 띄어 쓴 이름은 붙여서 한 번 더 찾는다.
        String joined = keyword.replaceAll("\\s+", "");
        if (found.isEmpty() && !joined.equals(keyword)) found = search(mention, joined, deadline);
        // 너무 짧거나 긴 이름은 이름 찾기처럼 비슷한 이름도 찾지 않는다.
        boolean searchable = keyword.length() >= DrugSearchService.MIN_NAME_LENGTH
                && keyword.length() <= DrugSearchService.MAX_NAME_LENGTH;
        if (found.isEmpty() && searchable) found = similar(mention, named, deadline);
        return found;
    }

    /** 보험코드로 찾은 행. 하나뿐이고 함께 말한 이름과 맞으면 함량 일치(미리 고름), 아니면 확인 불가로 둔다. */
    private List<Candidate> byCode(String mention, String named, List<JsonNode> rows) {
        var drugs = drugs(rows);
        boolean sure = drugs.size() == 1 && DrugNameSimilarity.consistent(named, drugs.getFirst().name());
        return drugs.stream().limit(PER_MENTION)
                .map(drug -> new Candidate(mention, drug, sure ? StrengthMatch.MATCH : StrengthMatch.UNKNOWN)).toList();
    }

    /** 비슷한 이름의 공식 후보(미리 고르지 않음). 낱알 목록은 준비된 것만 쓰고 기다리지 않는다. */
    private List<Candidate> similar(String mention, String named, ChatDeadline deadline) {
        var rows = new SimilarDrugNames(publicData, catalog::cachedRows).find(searchName(named), deadline.remaining());
        return drugs(rows).stream().map(drug -> new Candidate(mention, drug, StrengthMatch.UNKNOWN, true, null)).toList();
    }

    /** 필수 정보가 빠진 행을 뺀 약 캐시 후보(같은 품목은 한 번). */
    private List<PhotoCandidateData> drugs(List<JsonNode> rows) {
        var missingImages = new HashSet<String>();
        for (var row : rows) if (text(row, "ITEM_IMAGE", "ITEM_IMAGE_URL") == null && text(row, "ITEM_SEQ") != null)
            missingImages.add(text(row, "ITEM_SEQ"));
        Map<String, String> images = catalog.cachedImageUrls(missingImages);
        var unique = new LinkedHashMap<String, PhotoCandidateData>();
        for (var row : rows) {
            try {
                var drug = PhotoCandidateLookup.permitCandidate(row, images.get(text(row, "ITEM_SEQ")));
                unique.putIfAbsent(drug.itemSeq(), drug);
            } catch (ExternalApiException incomplete) {
                // 필수 정보가 빠진 행 하나가 다른 후보를 없애지 않게 그 행만 뺀다.
            }
        }
        return List.copyOf(unique.values());
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
        String unit = LIQUID.matcher(name).find() ? "mL" : "mg";
        name = SHORT_UNIT.matcher(name).replaceAll(match -> match.group(1) + unit);
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
