package org.semiproject.pillbadeumserver.image.service;

import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import tools.jackson.databind.JsonNode;

/** REG-007: 두 사진 경로가 같은 무추측 지시·상태 스키마를 사용함. 처방전 지시와 분리함. */
final class PillOcrRules {
  private PillOcrRules() {}
  static final String INSTRUCTIONS = """
      사진에 알약 한 개가 선명하게 있는지 singlePill로 표시하세요.
      사진1과 사진2는 업로드 순서이며 공공데이터의 공식 앞면·뒷면을 뜻하지 않습니다.
      사진1의 각인은 printFront, 사진2는 printBack에 보이는 문자·숫자만 기록하세요.
      각인·분할선 상태는 READABLE(확실히 읽힘), NONE(면 전체가 선명하고 표시 없음),
      UNREADABLE(흐림·반사·가림·일부 불확실·로고/기호만 있음) 중 하나입니다.
      READABLE 각인에는 읽힌 문자열을, 그 외 상태에는 null을 반환하세요.
      일부 글자나 약 이름에서 각인을 추측하거나 O/0·I/1 등을 바꾸지 마세요.
      READABLE 분할선은 MINUS·PLUS이며 NONE/UNREADABLE 값은 null입니다.
      모양·색상은 스키마의 공식 한글 목록 중 사진에서 확인한 값만 적으세요.
      모양·색상을 확인할 수 없으면 null로 반환하고 필수 칸을 채우려고 추측하지 마세요.
      itemName·entpName은 명확히 읽힐 때만 쓰고, 품목코드를 생성하지 마세요.
      약이 아니거나 읽지 못했으면 값을 만들어 채우지 마세요.
      """;

  static Map<String, Object> schema() {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("singlePill", Map.of("type", "boolean"));
    for (String name : List.of("itemName", "entpName", "printFront", "printBack", "shape",
        "colorClass1", "colorClass2", "lineFront", "lineBack"))
      fields.put(name, Map.of("type", List.of("string", "null")));
    fields.put("shape", Map.of("type", List.of("string", "null"), "enum",
        Arrays.asList("원형", "타원형", "장방형", "반원형", "삼각형", "사각형", "마름모형", "오각형", "육각형", "팔각형", "기타", null)));
    var color = Map.<String, Object>of("type", List.of("string", "null"), "enum",
        Arrays.asList("하양", "노랑", "주황", "분홍", "빨강", "갈색", "연두", "초록", "청록", "파랑", "남색", "자주", "보라", "회색", "검정", "투명", null));
    fields.put("colorClass1", color);
    fields.put("colorClass2", color);
    for (String name : List.of("printFrontState", "printBackState", "lineFrontState", "lineBackState"))
      fields.put(name, Map.of("type", "string", "enum", List.of("READABLE", "NONE", "UNREADABLE")));
    return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()), "additionalProperties", false);
  }

  static PillIdentification read(JsonNode output) {
    try { return PillIdentification.fromVision(output); }
    catch (BusinessException invalid) {
      throw ExternalApiException.failed("사진 판독", "각인 상태와 식별 단서 형식을 확인하지 못했습니다.", null);
    }
  }
}
