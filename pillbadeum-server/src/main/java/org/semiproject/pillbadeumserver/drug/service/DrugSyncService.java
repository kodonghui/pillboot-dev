package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import org.semiproject.pillbadeumserver.drug.domain.DrugCancellation;
import org.semiproject.pillbadeumserver.drug.domain.DrugRegulatoryType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** REG-005/008: 공식 품목을 확인해 drug FK를 보장함. 낱알0건은 허가 상세로 확인하며 외부 호출은 TX 밖임. */
@Service
public class DrugSyncService {

    private static final Logger log = LoggerFactory.getLogger(DrugSyncService.class);

    public static final String GRAIN_INFO_PATH = "/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03";
    public static final String SOURCE_NAME = "식품의약품안전처 의약품 낱알식별 정보";

    // 성분명(material)을 얻는 허가정보 목록 API. 팀 활용신청 주소와 다르면 이 상수만 바꾼다.
    public static final String PERMIT_INFO_PATH = "/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnInq08";
    // 한글 성분을 우선하며 없으면 영문 필드를 사용함. 대소문자는 구분하지 않음.
    public static final List<String> PERMIT_MATERIAL_FIELDS = List.of("MAIN_ITEM_INGR", "ITEM_INGR_NAME");
    // 같은 이름의 약(용량 · 제조사 다름)이 여러 건 나올 수 있어 넉넉히 받는다.
    private static final int PERMIT_SEARCH_ROWS = 100;

    // drug 테이블 컬럼 길이 (sql/01_PILLBADEUM_DB_SCRIPT.sql). 넘으면 잘라서 저장한다.
    private static final int LEN_NAME = 50;
    private static final int LEN_MAKER = 50;
    private static final int LEN_CLASS_NAME = 50;
    private static final int LEN_IMAGE_URL = 255;
    private static final int LEN_PRINT = 50;
    private static final int LEN_SHAPE = 50;
    private static final int LEN_COLOR = 50;
    private static final int LEN_LINE = 10;
    private static final int LEN_FORM = 10;

    private final PublicDataClient publicDataClient;
    private final DrugRepository drugRepository;

    public DrugSyncService(PublicDataClient publicDataClient, DrugRepository drugRepository) {
        this.publicDataClient = publicDataClient;
        this.drugRepository = drugRepository;
    }

    /* 목차. 1. DB 에 있으면 그대로 돌려주고, 없으면 낱알식별로 채운 뒤 돌려준다. */
    public Drug getOrSync(String itemSeq) {

        String key = normalizeItemSeq(itemSeq);
        Optional<Drug> saved = drugRepository.findById(key);
        if (saved.isPresent()) {
            var cached = saved.get();
            if (!needsRefresh(cached)) return cached;
            try { return syncByItemSeq(key); }
            catch (BusinessException unavailable) {
                // 상세 보강 실패가 이미 등록한 약과 풍부한 기존 캐시를 없애지 않음.
                log.warn("[drug sync] result=cache_retained");
                return cached;
            }
        }
        return syncByItemSeq(key);
    }

    /* 목차. 2. 낱알식별을 다시 조회해 drug 를 새로 만들거나 최신 값으로 덮어쓴다. */
    public Drug syncByItemSeq(String itemSeq) {

        String key = normalizeItemSeq(itemSeq);
        JsonNode found;
        boolean permitOnly;
        try {
            found = fetchGrainInfo(key);
            permitOnly = false;
        } catch (NotFoundException noGrainRecord) {
            // 시럽·연고 등 낱알 자료가 없는 약도 정확한 공식 품목으로 확인할 수 있음.
            found = fetchPermitInfo(key);
            permitOnly = true;
        }
        final JsonNode item = found;
        String material = permitOnly ? materialOf(item) : fetchMaterial(key, text(item, "ITEM_NAME"));
        Values values = Values.from(item, key, material, permitOnly);
        LocalDateTime now = LocalDateTime.now(org.semiproject.pillbadeumserver.common.config.TimeZoneConfig.ZONE);
        String source = permitOnly ? "PERMIT" : "MIXED";

        Drug drug = drugRepository.findById(key)
                .map(existing -> {
                    existing.sync(prefer(values.name, existing.getName()), prefer(values.maker, existing.getMaker()),
                            values.etcOtc, prefer(values.className, existing.getClassName()), prefer(values.imageUrl, existing.getImageUrl()),
                            prefer(values.printFront, existing.getPrintFront()), prefer(values.printBack, existing.getPrintBack()),
                            prefer(values.shape, existing.getShape()), prefer(values.color, existing.getColor()),
                            prefer(values.lineFront, existing.getLineFront()), prefer(values.lineBack, existing.getLineBack()),
                            prefer(values.form, existing.getForm()), prefer(values.material, existing.getMaterial()), now);
                    existing.recordMetadataSource((existing.getMetadataSource().equals("UNKNOWN") || existing.getMetadataSource().equals(source)) ? source : "MIXED", DrugCancellation.parse(item));
                    return existing;
                })
                .orElseGet(() -> {
                    var created = Drug.create(key, values.name, values.maker, values.etcOtc, values.className, values.imageUrl,
                            values.printFront, values.printBack, values.shape, values.color, values.lineFront,
                            values.lineBack, values.form, values.material, now);
                    created.recordMetadataSource(source, DrugCancellation.parse(item));
                    return created;
                });

        try {
            return drugRepository.save(drug);
        } catch (DataIntegrityViolationException e) {
            // 같은 약을 동시에 처음 저장한 경우: 먼저 저장된 행을 그대로 쓴다.
            log.info("[drug sync] 동시 저장 감지, 기존 행 사용 itemSeq={}", key);
            return drugRepository.findById(key).orElseThrow(() -> e);
        }
    }

    /* 설명. 낱알식별 API 에서 품목기준코드로 한 건을 찾는다. 없으면 404 */
    private JsonNode fetchGrainInfo(String itemSeq) {

        JsonNode body = publicDataClient.get(GRAIN_INFO_PATH, Map.of(
                "item_seq", itemSeq,
                "pageNo", 1,
                "numOfRows", 1));

        List<JsonNode> items = PublicDataClient.items(body);
        return items.stream()
                .filter(item -> itemSeq.equals(text(item, "ITEM_SEQ")))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("낱알식별 정보가 없는 약입니다. (itemSeq=" + itemSeq + ")"));
    }

    private JsonNode fetchPermitInfo(String itemSeq) {
        var body = publicDataClient.get(DrugGuideService.PERMIT_DETAIL_PATH,
                Map.of("item_seq", itemSeq, "pageNo", 1, "numOfRows", 1));
        return PublicDataClient.items(body).stream()
                .filter(item -> itemSeq.equals(textIgnoreCase(item, "ITEM_SEQ")))
                .findFirst().orElseThrow(() -> new NotFoundException("공식 허가정보에서 품목을 확인하지 못했습니다."));
    }

    /* 설명. 허가정보 API 에서 성분명을 찾는다. 없으면 null (→ Values.from 에서 404) */
    private String fetchMaterial(String itemSeq, String itemName) {

        // 허가정보 Service08 목록 API 는 item_seq 조건을 무시하고 다른 약을 돌려준다. (2026-10-19 확인)
        // 그래서 낱알식별에서 받은 제품명(item_name)으로 찾고, 결과 중 품목기준코드가 같은 것을 고른다.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("item_seq", itemSeq);
        if (itemName != null) {
            params.put("item_name", itemName);
        }
        params.put("pageNo", 1);
        params.put("numOfRows", PERMIT_SEARCH_ROWS);

        JsonNode body = publicDataClient.get(PERMIT_INFO_PATH, params);

        return PublicDataClient.items(body).stream()
                .filter(item -> itemSeq.equals(textIgnoreCase(item, "ITEM_SEQ")))
                .map(DrugSyncService::materialOf)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /* 설명. 허가정보 한 건에서 성분명을 꺼낸다.
     *  MAIN_ITEM_INGR 은 '[M040702]아세트아미노펜|[M223101]카페인무수물' 처럼 성분 코드가 붙어 오므로
     *  [코드] 를 지우고 '아세트아미노펜, 카페인무수물' 로 바꾼다.
     * */
    private static String materialOf(JsonNode item) {
        return org.semiproject.pillbadeumserver.drug.domain.DrugIngredient.fromOfficial(item);
    }

    /* 설명. 필드 이름 대소문자가 API 버전마다 달라서 대소문자를 무시하고 찾는다. */
    private static String textIgnoreCase(JsonNode item, String field) {

        String exact = text(item, field);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, JsonNode> property : item.properties()) {
            if (property.getKey().equalsIgnoreCase(field)) {
                return text(item, property.getKey());
            }
        }
        return null;
    }

    /* 설명. 낱알식별 응답 한 건 → drug 컬럼 값
     *  ----------------------------------------------------------------------------------
     *  ITEM_NAME → name, ENTP_NAME → maker, 낱알·허가의 전문/일반 구분 → etc_otc(ETC/OTC)
     *  CLASS_NAME → class_name, ITEM_IMAGE → image_url, PRINT_FRONT/BACK → print_front/back
     *  DRUG_SHAPE → shape, COLOR_CLASS1 → color (뒷면 색 COLOR_CLASS2 는 저장할 컬럼 없음)
     *  LINE_FRONT/BACK → line_front/back
     *  FORM_CODE_NAME(예: 필름코팅정, 경질캡슐제)을 정제 / 연질캡슐 / 경질캡슐 / 기타 로 묶은 값 → form   ※ 가정 (T3)
     *  허가정보 MAIN_ITEM_INGR(없으면 ITEM_INGR_NAME), 성분 코드 제거 → material (TEXT에 성분명 전체 보존)
     *  ----------------------------------------------------------------------------------
     *  NOT NULL 컬럼에 넣을 값이 없으면 저장할 수 없으므로 '식별할 수 없는 약' 으로 보고 404
     * */
    private record Values(String name, String maker, String etcOtc, String className, String imageUrl,
                          String printFront, String printBack, String shape, String color,
                          String lineFront, String lineBack, String form, String material) {

        static Values from(JsonNode item, String itemSeq, String material) {
            return from(item, itemSeq, material, false);
        }

        static Values from(JsonNode item, String itemSeq, String material, boolean permitOnly) {

            String formCodeName = text(item, "FORM_CODE_NAME");

            Values raw = new Values(
                    cut(text(item, "ITEM_NAME"), LEN_NAME),
                    cut(text(item, "ENTP_NAME"), LEN_MAKER),
                    DrugRegulatoryType.parse(item),
                    cut(text(item, "CLASS_NAME"), LEN_CLASS_NAME),
                    cut(text(item, "ITEM_IMAGE"), LEN_IMAGE_URL),
                    cut(text(item, "PRINT_FRONT"), LEN_PRINT),
                    cut(text(item, "PRINT_BACK"), LEN_PRINT),
                    cut(text(item, "DRUG_SHAPE"), LEN_SHAPE),
                    cut(text(item, "COLOR_CLASS1"), LEN_COLOR),
                    cut(text(item, "LINE_FRONT"), LEN_LINE),
                    cut(text(item, "LINE_BACK"), LEN_LINE),
                    cut(toForm(formCodeName), LEN_FORM),
                    org.semiproject.pillbadeumserver.drug.domain.DrugIngredient.forStorage(material));
            Values v = !permitOnly ? raw : new Values(raw.name, raw.maker, raw.etcOtc,
                    known(raw.className), raw.imageUrl, raw.printFront, raw.printBack, known(raw.shape),
                    known(raw.color), raw.lineFront, raw.lineBack, known(raw.form), known(raw.material));

            List<String> missing = new ArrayList<>();
            if (v.name == null) missing.add("name(ITEM_NAME)");
            if (v.maker == null) missing.add("maker(ENTP_NAME)");
            if (v.etcOtc == null) missing.add("전문/일반 구분");
            if (v.className == null) missing.add("class_name(CLASS_NAME)");
            if (v.shape == null) missing.add("shape(DRUG_SHAPE)");
            if (v.color == null) missing.add("color(COLOR_CLASS1)");
            if (v.form == null) missing.add("form(FORM_CODE_NAME)");
            if (v.material == null) missing.add("material(" + String.join("/", PERMIT_MATERIAL_FIELDS) + ")");

            if (!missing.isEmpty()) {
                log.info("[drug sync] 필수 값 없음 itemSeq={} missing={}", itemSeq, missing);
                throw new NotFoundException("낱알식별 정보가 부족해 등록할 수 없는 약입니다. (itemSeq=" + itemSeq
                        + ", 없는 값: " + String.join(", ", missing) + ")");
            }
            return v;
        }
    }

    /* 설명. 제형 분류 (가정 T3): 캡슐은 연질/경질로, 정(錠)으로 끝나는 제형은 정제로, 나머지는 기타 */
    private static String toForm(String formCodeName) {
        if (formCodeName == null) {
            return null;
        }
        if (formCodeName.contains("연질캡슐")) {
            return "연질캡슐";
        }
        if (formCodeName.contains("캡슐")) {
            return "경질캡슐";
        }
        if (formCodeName.contains("정")) {
            return "정제";
        }
        return "기타";
    }

    private static String normalizeItemSeq(String itemSeq) {
        String key = (itemSeq == null) ? "" : itemSeq.strip();
        if (key.isEmpty() || key.length() > 100 || !key.chars().allMatch(Character::isDigit)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "품목기준코드(itemSeq)를 확인해주세요.");
        }
        return key;
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString().strip();
        return (text.isEmpty() || "-".equals(text)) ? null : text;
    }

    private static String cut(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String known(String value) { return value == null ? "미확인" : value; }

    private static boolean unknown(String value) { return value == null || value.isBlank() || value.equals("미확인"); }
    private static String prefer(String incoming, String existing) { return !unknown(incoming) ? incoming : !unknown(existing) ? existing : incoming; }

    private static boolean needsRefresh(Drug cached) {
        var now = LocalDateTime.now(org.semiproject.pillbadeumserver.common.config.TimeZoneConfig.ZONE);
        if (cached.getSyncedAt().isBefore(now.minusDays(7)) || "UNKNOWN".equals(cached.getMetadataSource())) return true;
        boolean missing = unknown(cached.getMaterial()) || unknown(cached.getClassName()) || unknown(cached.getShape()) || unknown(cached.getColor()) || unknown(cached.getForm());
        // 허가자료만 있는 제형의 외형 미확인은 정상임. 클릭마다 낱알0건을 반복하지 않음.
        return missing && (!"PERMIT".equals(cached.getMetadataSource()) || cached.getSyncedAt().isBefore(now.minusMinutes(10)));
    }
}
