package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.DurWarning;
import org.semiproject.pillbadeumserver.drug.domain.DurWarningRepository;
import org.semiproject.pillbadeumserver.drug.dto.DrugGuideResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/* 설명. 약 근거 출처 (GET /api/drugs/{itemSeq}/guide)
 *  ----------------------------------------------------------------------------------
 *  1) drug 테이블에 있는 약인지 확인 (없으면 404 — 등록 · 조회를 거친 약만 근거를 보여준다)
 *  2) e약은요(의약품개요정보)를 품목기준코드로 조회 → 효능 · 사용법 · 주의사항 · 상호작용 · 부작용 · 보관법
 *  3) e약은요에 없으면(주로 전문의약품) 허가정보 상세로 한 번 더 조회 → 효능효과 · 용법용량 · 주의사항 · 보관방법
 *  4) dur_warning 에 저장된 DUR 금기 · 주의를 함께 돌려준다.
 *  ----------------------------------------------------------------------------------
 *  - 본문은 DB 에 저장하지 않는다. 출처명 · 자료 수정일 · 조회 시각 · 상담 안내 문구를 같이 내려준다.
 *  - 품목기준코드가 정확히 같은 결과만 쓴다. (이름이 비슷한 다른 약을 근거로 보여주지 않기 위해)
 *  - 외부 API 를 기다리는 동안 DB 연결을 잡지 않도록 @Transactional 을 붙이지 않는다.
 *  - 공공데이터 호출 실패 · 시간 초과는 PublicDataClient 가 503 으로 바꿔 던진다.
 *  - dur_warning 은 지금 챗봇의 DUR 조회(chat.DurCacheStore)가 채운다. 이 서비스는 읽기만 한다.
 *    프로필(출생년도 · 임신 여부)에 해당하는지 판단하는 'DUR 주의' 는 아직 없다. (계획 문서 D5)
 * */
@Service
public class DrugGuideService {

    public static final String EASY_DRUG_PATH = "/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList";
    public static final String EASY_DRUG_SOURCE = "식품의약품안전처 의약품개요정보(e약은요)";
    public static final String PERMIT_DETAIL_PATH = "/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnDtlInq08";
    public static final String PERMIT_SOURCE = "식품의약품안전처 의약품 제품 허가정보";

    // 항목 하나가 이보다 길면 본문 대신 생략 안내를 넣는다. (의료 정보를 중간에서 자르지 않고, 항목을 말없이 빼지도 않는다)
    // 전문의약품의 '사용상의 주의사항'은 실제로 이만큼 길 수 있어 비정상 응답으로 단정하지 않는다.
    private static final int MAX_SECTION_LENGTH = 20_000;
    static final String OMITTED_SECTION = "내용이 길어 앱에서는 보여 드리지 않아요. "
            + "의약품안전나라(nedrug.mfds.go.kr)에서 이 약의 전체 내용을 확인해 주세요.";

    // e약은요 필드 → 화면 항목 (화면 표시 순서)
    private static final List<Field> EASY_FIELDS = List.of(
            new Field("efcyQesitm", "EFFICACY", "효능"),
            new Field("useMethodQesitm", "USAGE", "사용법"),
            new Field("atpnWarnQesitm", "WARNING", "경고"),
            new Field("atpnQesitm", "PRECAUTION", "주의사항"),
            new Field("intrcQesitm", "INTERACTION", "상호작용"),
            new Field("seQesitm", "SIDE_EFFECT", "부작용"),
            new Field("depositMethodQesitm", "STORAGE", "보관법"));

    // 허가정보 상세 필드 → 화면 항목 (e약은요에 없는 약일 때만 사용)
    private static final List<Field> PERMIT_FIELDS = List.of(
            new Field("EE_DOC_DATA", "EFFICACY", "효능효과"),
            new Field("UD_DOC_DATA", "USAGE", "용법용량"),
            new Field("NB_DOC_DATA", "PRECAUTION", "사용상의 주의사항"),
            new Field("STORAGE_METHOD", "STORAGE", "보관방법"));

    private final PublicDataClient publicDataClient;
    private final DrugRepository drugRepository;
    private final DurWarningRepository durWarningRepository;

    public DrugGuideService(PublicDataClient publicDataClient,
                            DrugRepository drugRepository,
                            DurWarningRepository durWarningRepository) {
        this.publicDataClient = publicDataClient;
        this.drugRepository = drugRepository;
        this.durWarningRepository = durWarningRepository;
    }

    /* 목차. 1. 근거 출처 조회 */
    public DrugGuideResponse getGuide(String itemSeq) {

        String key = normalizeItemSeq(itemSeq);
        Drug drug = drugRepository.findById(key)
                .orElseThrow(() -> new NotFoundException("약 정보를 찾을 수 없습니다. (itemSeq=" + key + ")"));

        LocalDateTime retrievedAt = LocalDateTime.now(TimeZoneConfig.ZONE);

        Official official = fetch(EASY_DRUG_PATH, "itemSeq", "itemSeq", "itemName", "updateDe", EASY_FIELDS, EASY_DRUG_SOURCE, key)
                .or(() -> fetch(PERMIT_DETAIL_PATH, "item_seq", "ITEM_SEQ", "ITEM_NAME", "CHANGE_DATE", PERMIT_FIELDS, PERMIT_SOURCE, key))
                .orElse(null);

        List<DrugGuideResponse.DurItem> durWarnings = durWarningRepository.findAllByDrug_ItemSeqOrderByDurWarningIdAsc(key)
                .stream()
                .map(DrugGuideResponse.DurItem::from)
                .toList();

        return new DrugGuideResponse(
                key,
                drug.getName(),
                official != null,
                official != null ? official.sourceName() : null,
                official != null ? official.itemName() : null,
                official != null ? official.referenceDate() : null,
                retrievedAt,
                official != null ? official.sections() : List.of(),
                durWarnings,
                DurWarning.SOURCE_NAME,
                DrugGuideResponse.NOTICE);
    }

    /* 설명. 공공데이터 한 곳을 품목기준코드로 조회한다.
     *  품목기준코드가 정확히 같은 결과가 없거나, 있어도 쓸 만한 항목이 하나도 없으면 비어 있는 Optional.
     * */
    private Optional<Official> fetch(String path, String paramName, String seqField, String nameField, String dateField,
                                     List<Field> fields, String sourceName, String itemSeq) {

        JsonNode body = publicDataClient.get(path, Map.of(
                paramName, itemSeq,
                "pageNo", 1,
                "numOfRows", 1));

        Optional<JsonNode> matched = PublicDataClient.items(body).stream()
                .filter(item -> itemSeq.equals(item.path(seqField).asString("").strip()))
                .findFirst();
        if (matched.isEmpty()) {
            return Optional.empty();
        }

        JsonNode item = matched.get();
        List<DrugGuideResponse.Section> sections = new ArrayList<>();
        for (Field field : fields) {
            String text = clean(item.path(field.apiName()).asString(""));
            if (!text.isEmpty()) {
                sections.add(new DrugGuideResponse.Section(field.key(), field.title(),
                        text.length() <= MAX_SECTION_LENGTH ? text : OMITTED_SECTION));
            }
        }
        if (sections.isEmpty()) {
            return Optional.empty();
        }

        LocalDate referenceDate = parseDate(item.path(dateField).asString(""));
        String itemName = clean(item.path(nameField).asString(""));
        return Optional.of(new Official(sourceName, itemName.isEmpty() ? null : itemName, referenceDate, List.copyOf(sections)));
    }

    /* 설명. 공공데이터 본문의 HTML · XML 태그와 엔티티를 지우고 공백을 하나로 줄인다. */
    static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return HtmlUtils.htmlUnescape(raw)
                .replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1")
                .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /* 설명. '20240509' 또는 '2024-05-09 ...' 형식을 날짜로. 알 수 없으면 null */
    static LocalDate parseDate(String value) {
        try {
            if (value.matches("\\d{8}")) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            }
            if (value.length() >= 10) {
                return LocalDate.parse(value.substring(0, 10));
            }
        } catch (DateTimeParseException ignored) {
            // 날짜를 알 수 없으면 표시하지 않는다.
        }
        return null;
    }

    private static String normalizeItemSeq(String itemSeq) {
        String key = (itemSeq == null) ? "" : itemSeq.strip();
        if (key.isEmpty() || key.length() > 100 || !key.chars().allMatch(Character::isDigit)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "품목기준코드(itemSeq)를 확인해주세요.");
        }
        return key;
    }

    private record Field(String apiName, String key, String title) {
    }

    private record Official(String sourceName, String itemName, LocalDate referenceDate,
                            List<DrugGuideResponse.Section> sections) {
    }
}
