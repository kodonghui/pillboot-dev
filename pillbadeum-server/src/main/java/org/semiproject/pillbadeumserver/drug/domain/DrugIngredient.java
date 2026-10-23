package org.semiproject.pillbadeumserver.drug.domain;

import tools.jackson.databind.JsonNode;

/** 공식 성분을 한글 우선으로 해석하고 TEXT에 원문 전체를 보존함. */
public final class DrugIngredient {
  private DrugIngredient() {}

  public static String fromOfficial(JsonNode row) {
    for (String field : java.util.List.of("MAIN_ITEM_INGR", "ITEM_INGR_NAME")) {
      for (var property : row.properties()) {
        if (!property.getKey().equalsIgnoreCase(field)) continue;
        String value = java.util.Arrays.stream(property.getValue().asString("").split("\\|"))
            .map(part -> part.replaceAll("\\[[^\\]]*]", "").strip()).filter(part -> !part.isBlank())
            .distinct().collect(java.util.stream.Collectors.joining(", "));
        if (!value.isBlank()) return forStorage(value);
      }
    }
    return null;
  }

  public static String forStorage(String value) {
    return value;
  }
}
