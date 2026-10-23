package org.semiproject.pillbadeumserver.image.service;

import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.drug.service.PrescriptionDrugLookup;
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
    var rows = new PrescriptionDrugLookup(client).find(name, maker);
    var ordered = rows.stream().sorted(Comparator.<JsonNode>comparingInt(row -> Boolean.TRUE.equals(org.semiproject.pillbadeumserver.drug.domain.DrugCancellation.parse(row)) ? 1 : 0)
        .thenComparingInt(row -> exact(name, text(row, "ITEM_NAME")) ? 0 : 1)
        .thenComparingInt(row -> exact(maker, text(row, "ENTP_NAME")) ? 0 : 1)).toList();
    return validated(ordered, null);
  }

  public List<PhotoCandidateData> pill(PillIdentification clues) {
    return pillResult(clues).candidates();
  }

  /** REG-007: 알약 사진은 거의 항상 모양·색을 읽어 낱알 목록이 필요하다. 목록이 없으면 판독 전에 준비 중(503)으로 끝낸다. */
  public void requirePillCatalog() {
    catalog.rows();
  }

  public record PillResult(List<PhotoCandidateData> candidates, int totalCandidateCount,
      int excludedCount, boolean hasTiedCandidates, List<String> missingFields, String catalogVersion) {
    public String status() {
      return !missingFields.isEmpty() ? "UNREADABLE" : totalCandidateCount == 0 ? "NOT_FOUND" : "OK";
    }
    public String lookupStatus() { return totalCandidateCount == 0 ? "NOT_FOUND" : excludedCount > 0 ? "PARTIAL" : "OK"; }
  }

  public PillResult pillResult(PillIdentification clues) {
    var rows = catalog.rows();
    var missing = PillSimilarity.missingFields(clues, rows);
    String version = catalogVersion(rows);
    if (!missing.isEmpty()) return new PillResult(List.of(), 0, 0, false, missing, version);
    var valid = new LinkedHashMap<String, PhotoCandidateData>();
    int excluded = 0, rank = 0;
    String previous = null;
    boolean tied = false;
    for (var match : PillSimilarity.rank(clues, rows)) {
      PhotoCandidateData value;
      try { value = candidate(match.item(), match.printMatched()); }
      catch (ExternalApiException invalid) { excluded++; continue; }
      if (valid.containsKey(value.itemSeq())) continue;
      String tuple = match.exactImprints() + ":" + match.exactLines();
      if (tuple.equals(previous)) tied = true;
      else rank++;
      previous = tuple;
      valid.put(value.itemSeq(), value.withMatch(match.orientation(), rank, match.matchDetails()));
    }
    if (valid.isEmpty() && excluded > 0)
      throw ExternalApiException.failed("공공데이터", "후보의 필수 정보를 확인하지 못했습니다.", null);
    return new PillResult(valid.values().stream().limit(5).toList(), valid.size(), excluded, tied, List.of(), version);
  }

  private static String catalogVersion(List<JsonNode> rows) {
    try {
      var hash = java.security.MessageDigest.getInstance("SHA-256");
      for (var row : rows) {
        hash.update(row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hash.update((byte) '\n');
      }
      return java.util.HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
    String material = org.semiproject.pillbadeumserver.drug.domain.DrugIngredient.fromOfficial(row);
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
        material == null ? "미확인" : material,
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
