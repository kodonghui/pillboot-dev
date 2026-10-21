package org.semiproject.pillbadeumserver.image.service;

import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillIdentificationCandidate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Ranks official catalog records; recognition never invents or replaces product names. */
@Service
public class PillDrugIdentificationService {
    private static final String PRODUCT_PERMIT_PATH = "/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnInq08";
    private static final String EASY_DRUG_PATH = "/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList";
    private final PublicDataClient publicData;
    private final PillCatalog catalog;

    public PillDrugIdentificationService(PublicDataClient publicData, PillCatalog catalog) {
        this.publicData = publicData;
        this.catalog = catalog;
    }

    public List<PillIdentificationCandidate> identify(PillIdentification input) {
        if (!PillSimilarity.hasCriteria(input)) throw new BusinessException(ErrorCode.INVALID_INPUT,
                "약 이름, 각인, 모양 또는 색상 중 하나 이상이 필요합니다.");
        List<PillSimilarity.Match> ranked = PillSimilarity.rank(input, catalog.rows()).stream().limit(5).toList();
        List<PillIdentificationCandidate> result = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            var match = ranked.get(index);
            JsonNode permit = null, easy = null;
            if (PillSimilarity.present(input.itemName()) || PillSimilarity.present(input.entpName())) {
                String code = text(match.item(), "ITEM_SEQ");
                // Optional guide failures must not erase valid identification candidates.
                permit = findByItemSeq(PRODUCT_PERMIT_PATH, "item_seq", "ITEM_SEQ", code, text(match.item(), "ITEM_NAME"));
                easy = findByItemSeq(EASY_DRUG_PATH, "itemSeq", "itemSeq", code, null);
            }
            result.add(toCandidate(index + 1, match, permit, easy));
        }
        return List.copyOf(result);
    }

    private JsonNode findByItemSeq(String path, String queryName, String responseName, String code, String name) {
        Map<String, Object> query = new LinkedHashMap<>(Map.of(queryName, code, "pageNo", 1, "numOfRows", 100));
        if (name != null) query.put("item_name", name);
        try {
            return PublicDataClient.items(publicData.get(path, query)).stream()
                    .filter(item -> code.equals(text(item, responseName))).findFirst().orElse(null);
        } catch (ExternalApiException unavailable) { return null; }
    }

    private static PillIdentificationCandidate toCandidate(int rank, PillSimilarity.Match match, JsonNode permit, JsonNode easy) {
        JsonNode pill = match.item();
        return new PillIdentificationCandidate(rank, Math.round(match.score() * 1000) / 10.0,
                text(pill, "ITEM_SEQ"), text(pill, "ITEM_NAME"), text(pill, "ENTP_NAME"), text(pill, "ITEM_IMAGE"),
                text(pill, "PRINT_FRONT"), text(pill, "PRINT_BACK"), text(pill, "DRUG_SHAPE"),
                text(pill, "COLOR_CLASS1"), text(pill, "COLOR_CLASS2"), text(pill, "LINE_FRONT"), text(pill, "LINE_BACK"),
                first(text(pill, "ETC_OTC_NAME"), text(permit, "ETC_OTC_NAME"), text(permit, "SPCLTY_PBLC"), text(permit, "ETC_OTC_CODE")),
                first(text(pill, "CLASS_NAME"), text(permit, "CLASS_NAME")),
                first(text(permit, "ITEM_INGR_NAME"), text(permit, "MAIN_ITEM_INGR")),
                first(text(pill, "ITEM_PERMIT_DATE"), text(permit, "ITEM_PERMIT_DATE")),
                text(easy, "efcyQesitm"), text(easy, "useMethodQesitm"), text(easy, "atpnWarnQesitm"),
                text(easy, "atpnQesitm"), text(easy, "intrcQesitm"), text(easy, "seQesitm"), text(easy, "depositMethodQesitm"));
    }

    private static String text(JsonNode item, String field) { return PillSimilarity.text(item, field); }
    private static String first(String... values) { return Arrays.stream(values).filter(Objects::nonNull).findFirst().orElse(null); }
}
