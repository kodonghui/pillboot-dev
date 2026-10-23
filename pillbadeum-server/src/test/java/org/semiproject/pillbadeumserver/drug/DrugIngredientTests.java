package org.semiproject.pillbadeumserver.drug;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.drug.domain.DrugIngredient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

class DrugIngredientTests {
  final JsonMapper json = new JsonMapper();

  @Test void koreanNamesAndCodeRemovalPreserveTheFullCompoundIngredient() {
    String korean = "한글복합성분".repeat(8);
    var row = json.valueToTree(Map.of("MAIN_ITEM_INGR", "[M123]" + korean + "|[M456]보조성분",
        "ITEM_INGR_NAME", "English alternative"));
    assertThat(DrugIngredient.fromOfficial(row)).isEqualTo(korean + ", 보조성분");
  }

  @ParameterizedTest
  @ValueSource(ints = {30, 100, 101, 416, 1000})
  void compoundIngredientsArePreservedPastTheFormerOneHundredCharacterLimit(int length) {
    String ingredient = "가".repeat(length);
    var row = json.valueToTree(Map.of("MAIN_ITEM_INGR", ingredient));
    assertThat(DrugIngredient.fromOfficial(row)).isEqualTo(ingredient);
    assertThat(DrugIngredient.forStorage(ingredient)).isEqualTo(ingredient);
  }

  @Test void absentKoreanNameFallsBackToTheCompleteEnglishIngredient() {
    String english = "Compound ingredient longer than the previous thirty character column";
    assertThat(DrugIngredient.fromOfficial(json.valueToTree(Map.of("MAIN_ITEM_INGR", "", "ITEM_INGR_NAME", english)))).isEqualTo(english);
  }
}
