package org.semiproject.pillbadeumserver.drug.domain;

import tools.jackson.databind.JsonNode;

/** REG-004/007: 공식 상태가 명확한 경우만 취하 여부로 해석함. 누락을 정상으로 추측하지 않음. */
public final class DrugCancellation {
  private DrugCancellation() {}
  public static Boolean parse(JsonNode row) {
    for (var field : row.properties()) {
      if (!field.getKey().equalsIgnoreCase("CANCEL_NAME")) continue;
      String label = field.getValue().asString("").strip();
      if (label.contains("정상")) return false;
      if (label.contains("취하") || label.contains("취소") || label.contains("만료")) return true;
    }
    return null;
  }
}
