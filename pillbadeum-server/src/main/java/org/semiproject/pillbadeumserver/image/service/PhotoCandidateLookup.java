package org.semiproject.pillbadeumserver.image.service;

import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** REG-003/007: 실제 공식 코드만 후보로 만든다. 조회는 DB 트랜잭션 없이 공통 클라이언트를 사용한다. */
@Service
public class PhotoCandidateLookup {
  private final PublicDataClient client;

  public PhotoCandidateLookup(PublicDataClient client) {
    this.client = client;
  }

  public List<PhotoCandidateData> prescription(String name, String maker) {
    var query = query(name, maker);
    var rows = PublicDataClient.items(client.get(DrugSyncService.PERMIT_INFO_PATH, query));
    return rows.stream()
        .sorted(
            Comparator.<JsonNode>comparingInt(row -> exact(name, text(row, "ITEM_NAME")) ? 0 : 1)
                .thenComparingInt(row -> exact(maker, text(row, "ENTP_NAME")) ? 0 : 1))
        .map(row -> candidate(row, null))
        .filter(Objects::nonNull)
        .collect(
            java.util.stream.Collectors.toMap(
                PhotoCandidateData::itemSeq, c -> c, (a, b) -> a, LinkedHashMap::new))
        .values()
        .stream()
        .limit(5)
        .toList();
  }

  public List<PhotoCandidateData> pill(PillIdentification clues) {
    var rows =
        PublicDataClient.items(
            client.get(DrugSyncService.GRAIN_INFO_PATH, query(clues.itemName(), clues.entpName())));
    var unique = new LinkedHashMap<String, JsonNode>();
    for (var row : rows)
      if (text(row, "ITEM_SEQ") != null) unique.putIfAbsent(text(row, "ITEM_SEQ"), row);
    return unique.values().stream()
        .sorted(
            Comparator.<JsonNode>comparingDouble(row -> score(clues, row))
                .reversed()
                .thenComparingInt(row -> exact(clues.itemName(), text(row, "ITEM_NAME")) ? 0 : 1)
                .thenComparing(row -> text(row, "ITEM_SEQ")))
        .limit(5)
        .map(row -> candidate(row, printMatch(clues, row)))
        .filter(Objects::nonNull)
        .toList();
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
    String raw = text(row, "ETC_OTC_NAME");
    String type =
        raw == null
            ? null
            : raw.contains("전문") || raw.equals("ETC")
                ? "ETC"
                : raw.contains("일반") || raw.equals("OTC") ? "OTC" : null;
    if (code == null
        || !code.matches("[0-9]{1,100}")
        || name == null
        || maker == null
        || type == null)
      throw ExternalApiException.failed("공공데이터", "후보의 품목코드·이름·제약사·전문/일반 구분을 확인하지 못했습니다.", null);
    String cancel = text(row, "CANCEL_NAME");
    Boolean cancelled = cancel == null ? null : !cancel.contains("정상");
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

  private static double score(PillIdentification c, JsonNode row) {
    return Math.max(orientation(c, row, false), orientation(c, row, true));
  }

  private static double orientation(PillIdentification c, JsonNode row, boolean reversed) {
    String front = reversed ? "BACK" : "FRONT", back = reversed ? "FRONT" : "BACK";
    return match(c.printFront(), text(row, "PRINT_" + front)) * 8
        + match(c.printBack(), text(row, "PRINT_" + back)) * 8
        + match(c.shape(), text(row, "DRUG_SHAPE")) * 2
        + match(c.colorClass1(), text(row, reversed ? "COLOR_CLASS2" : "COLOR_CLASS1"))
        + match(c.colorClass2(), text(row, reversed ? "COLOR_CLASS1" : "COLOR_CLASS2"))
        + match(c.lineFront(), text(row, "LINE_" + front))
        + match(c.lineBack(), text(row, "LINE_" + back));
  }

  private static Boolean printMatch(PillIdentification c, JsonNode row) {
    if (!present(c.printFront()) && !present(c.printBack())) return null;
    return pair(c.printFront(), text(row, "PRINT_FRONT"), c.printBack(), text(row, "PRINT_BACK"))
        || pair(c.printFront(), text(row, "PRINT_BACK"), c.printBack(), text(row, "PRINT_FRONT"));
  }

  private static boolean pair(String a, String x, String b, String y) {
    return (!present(a) || exact(a, x)) && (!present(b) || exact(b, y));
  }

  private static int match(String a, String b) {
    return present(a) && exact(a, b) ? 1 : 0;
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
    return s != null && !s.isBlank();
  }

  static String text(JsonNode node, String field) {
    for (var e : node.properties())
      if (e.getKey().equalsIgnoreCase(field)) {
        String s = e.getValue().isNull() ? null : e.getValue().asString("").strip();
        return !present(s) || "-".equals(s) ? null : s;
      }
    return null;
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
