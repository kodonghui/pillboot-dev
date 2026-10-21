package org.semiproject.pillbadeumserver.drug.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** REG-003/005/008: 낱알·허가 목록·허가 상세의 전문/일반 구분을 해석함. 미확인 값은 추정하지 않음. */
public final class DrugRegulatoryType {
  private static final List<String> FIELDS = List.of("ETC_OTC_NAME", "SPCLTY_PBLC", "ETC_OTC_CODE");

  private DrugRegulatoryType() {}

  public static String parse(JsonNode row) {
    for (String name : FIELDS) {
      for (var field : row.properties()) {
        if (!field.getKey().equalsIgnoreCase(name)) continue;
        String value = field.getValue().asString("").strip();
        if (value.contains("전문") || value.equals("ETC")) return Drug.ETC;
        if (value.contains("일반") || value.equals("OTC")) return Drug.OTC;
      }
    }
    return null;
  }
}
