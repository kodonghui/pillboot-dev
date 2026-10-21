package org.semiproject.pillbadeumserver.image.service;

import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.drug.domain.DrugRegulatoryType;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** REG-003/007: 실제 공식 코드만 후보로 만든다. 조회는 DB 트랜잭션 없이 공통 클라이언트를 사용한다. */
@Service
public class PhotoCandidateLookup {
  private final PublicDataClient client;
  private final PillCatalog catalog;

  public PhotoCandidateLookup(PublicDataClient client, PillCatalog catalog) {
    this.client = client;
    this.catalog = catalog;
  }

  public record Result(List<PhotoCandidateData> candidates, int excludedCount) {
    public String status() { return candidates.isEmpty() ? "NOT_FOUND" : excludedCount > 0 ? "PARTIAL" : "OK"; }
  }

  public List<PhotoCandidateData> prescription(String name, String maker) {
    return prescriptionResult(name, maker).candidates();
  }

  public Result prescriptionResult(String name, String maker) {
    var rows = PublicDataClient.items(client.get(DrugSyncService.PERMIT_INFO_PATH, query(name, maker)));
    var ordered = rows.stream().sorted(Comparator.<JsonNode>comparingInt(row -> Boolean.TRUE.equals(org.semiproject.pillbadeumserver.drug.domain.DrugCancellation.parse(row)) ? 1 : 0)
        .thenComparingInt(row -> exact(name, text(row, "ITEM_NAME")) ? 0 : 1)
        .thenComparingInt(row -> exact(maker, text(row, "ENTP_NAME")) ? 0 : 1)).toList();
    return validated(ordered, null);
  }

  public List<PhotoCandidateData> pill(PillIdentification clues) {
    return pillResult(clues).candidates();
  }

  public Result pillResult(PillIdentification clues) {
    if (!PillSimilarity.hasCriteria(clues)) throw new IllegalArgumentException("약 식별 단서가 필요합니다.");
    var rows = PillSimilarity.hasVisualCriteria(clues) ? catalog.rows()
        : PublicDataClient.items(client.get(DrugSyncService.GRAIN_INFO_PATH, query(clues.itemName(), clues.entpName())));
    var ordered = PillSimilarity.rank(clues, rows).stream().map(PillSimilarity.Match::item).toList();
    return validated(ordered, clues);
  }

  /** 후보 하나의 불완전함이 정상 후보를 없애지 않도록 행별 검증 후 중복/상한을 적용함. */
  private static Result validated(List<JsonNode> rows, PillIdentification clues) {
    var valid = new LinkedHashMap<String, PhotoCandidateData>();
    int excluded = 0;
    for (var row : rows) {
      try {
        var value = candidate(row, clues == null ? null : PillSimilarity.printsMatch(clues, row));
        valid.putIfAbsent(value.itemSeq(), value);
      } catch (ExternalApiException invalidCandidate) { excluded++; }
    }
    if (valid.isEmpty() && excluded > 0)
      throw ExternalApiException.failed("공공데이터", "후보의 필수 정보를 확인하지 못했습니다.", null);
    return new Result(valid.values().stream().limit(5).toList(), excluded);
  }

  private static Map<String, Object> query(String name, String maker) {
    var query = new LinkedHashMap<String, Object>();
    if (present(name)) query.put("item_name", name.strip());
    if (present(maker)) query.put("entp_name", maker.strip());
    if (query.isEmpty()) throw new IllegalArgumentException("이름 또는 제약사가 필요합니다.");
    query.put("pageNo", 1);
    query.put("numOfRows", 100);
    return query;
  }

  private static PhotoCandidateData candidate(JsonNode row, Boolean matched) {
    String code = text(row, "ITEM_SEQ"),
        name = text(row, "ITEM_NAME"),
        maker = text(row, "ENTP_NAME");
    String type = DrugRegulatoryType.parse(row);
    if (code == null
        || !code.matches("[0-9]{1,100}")
        || name == null
        || maker == null
        || type == null)
      throw ExternalApiException.failed("공공데이터", "후보의 품목코드·이름·제약사·전문/일반 구분을 확인하지 못했습니다.", null);
    Boolean cancelled = org.semiproject.pillbadeumserver.drug.domain.DrugCancellation.parse(row);
    String material = first(row, "ITEM_INGR_NAME", "MAIN_ITEM_INGR");
    if (material != null) material = material.replaceAll("\\[[^\\]]*]", "").replace('|', ',');
    return new PhotoCandidateData(
        code,
        cut(name, 50),
        cut(maker, 50),
        type,
        known(text(row, "CLASS_NAME"), 50),
        cut(first(row, "ITEM_IMAGE", "ITEM_IMAGE_URL"), 255),
        cut(text(row, "PRINT_FRONT"), 50),
        cut(text(row, "PRINT_BACK"), 50),
        known(text(row, "DRUG_SHAPE"), 50),
        known(text(row, "COLOR_CLASS1"), 50),
        cut(text(row, "LINE_FRONT"), 10),
        cut(text(row, "LINE_BACK"), 10),
        known(first(row, "FORM_CODE_NAME", "CHART"), 10),
        known(material, 30),
        matched,
        cancelled);
  }

  private static boolean exact(String a, String b) {
    return present(a) && normalize(a).equals(normalize(b));
  }

  private static String normalize(String s) {
    if (s == null) return "";
    return s.replace("하양", "흰색")
        .replace("하얀색", "흰색")
        .replace("WHITE", "흰색")
        .replaceAll("[^\\p{L}\\p{N}]", "")
        .toUpperCase(Locale.ROOT);
  }

  private static boolean present(String s) {
    return PillSimilarity.present(s);
  }

  static String text(JsonNode node, String field) {
    return PillSimilarity.text(node, field);
  }

  private static String first(JsonNode row, String... fields) {
    for (String field : fields) {
      String s = text(row, field);
      if (s != null) return s;
    }
    return null;
  }

  private static String cut(String s, int length) {
    return s == null
        ? null
        : s.substring(
            0, s.offsetByCodePoints(0, Math.min(length, s.codePointCount(0, s.length()))));
  }

  // 비낱알 외형 등 응답에 없는 값은 의료 사실을 추측하지 않고 명확히 '미확인'으로 표시한다.
  private static String known(String s, int length) {
    return s == null ? "미확인" : cut(s, length);
  }
}
