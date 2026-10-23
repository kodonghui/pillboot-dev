package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;
import static org.semiproject.pillbadeumserver.image.domain.PillIdentification.ObservationState.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.service.PillSimilarity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PillSimilarityTests {
  final JsonMapper json = new JsonMapper();

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "pentagon|light green|오각형|연두", "pentagonal|연두색|오각형|연두",
      "hexagonal|yellow|육각형|노랑", "square|teal|사각형|청록",
      "triangle|navy blue|삼각형|남색", "diamond|maroon|마름모형|자주",
      "octagon|transparent|팔각형|투명", "semicircular|white|반원형|하양",
      "rectangular|white|장방형|하양", "긴 타원형|흰색|장방형|하양"
  })
  void visualVocabularyAliasesFindTheExactOfficialPillWithoutWeakeningItsImprint(
      String shape, String color, String officialShape, String officialColor) {
    var target = (tools.jackson.databind.node.ObjectNode) row("1", "A", "B");
    target.put("DRUG_SHAPE", officialShape); target.put("COLOR_CLASS1", officialColor);
    var c = new PillIdentification(null, null, "A", "B", shape, color, null, null, null);
    assertThat(PillSimilarity.missingFields(c, List.of(target))).isEmpty();
    assertThat(PillSimilarity.rank(c, List.of(target))).singleElement()
        .satisfies(m -> assertThat(m.exactImprints()).isEqualTo(2));
    var wrong = new PillIdentification(null, null, "X", "B", shape, color, null, null, null);
    assertThat(PillSimilarity.rank(wrong, List.of(target))).isEmpty();
  }

  @Test
  void shapeAndColorNeverStandInForAReadableImprint() {
    var c = clues(null, null);
    assertThat(PillSimilarity.missingFields(c, List.of(row("1", "A", "B")))).containsExactly("IMPRINT");
    assertThat(PillSimilarity.rank(c, List.of(row("1", "A", "B")))).isEmpty();
  }

  @Test
  void missingFieldsAreOrderedAndUnrecognizedVisualValuesCannotPass() {
    var c = new PillIdentification(null, null, null, null, "알수없는모양", "무지개색", null, null, null);
    assertThat(PillSimilarity.missingFields(c, List.of(row("1", "A", "B"))))
        .containsExactly("IMPRINT", "SHAPE", "COLOR");
  }

  @Test
  void swappedSidesAndUnicodeWidthHaveTheSameCandidateAndActualEvidence() {
    var target = row("1", "DK", "10");
    var a = PillSimilarity.rank(clues("ＤＫ", "10"), List.of(target)).getFirst();
    var b = PillSimilarity.rank(clues("10", "D K"), List.of(target)).getFirst();
    assertThat(a.orientation()).isEqualTo("DIRECT");
    assertThat(b.orientation()).isEqualTo("SWAPPED");
    assertThat(a.exactImprints()).isEqualTo(2).isEqualTo(b.exactImprints());
    assertThat(b.matchDetails()).filteredOn(d -> d.field().equals("printFront")).singleElement()
        .satisfies(d -> { assertThat(d.referenceField()).isEqualTo("printBack"); assertThat(d.result()).isEqualTo("MATCH"); });
  }

  @Test
  void wrongDoseOcrSubstitutionAndPunctuationAreNotFuzzyMatches() {
    var c = clues("A0", "10");
    assertThat(PillSimilarity.rank(c, List.of(row("1", "A0", "10"), row("2", "AO", "10"),
        row("3", "A0", "40"), row("4", "A-0", "10"), row("5", "AI", "10"))))
        .extracting(m -> PillSimilarity.text(m.item(), "ITEM_SEQ")).containsExactly("1");
  }

  @Test
  void productNameCannotCompensateForContradictoryImprints() {
    var c = new PillIdentification("알약", null, "A", "10", "원형", "하양", null, null, null);
    assertThat(PillSimilarity.rank(c, List.of(row("1", "A", "40"), row("2", "A", "10"))))
        .extracting(m -> PillSimilarity.text(m.item(), "ITEM_SEQ")).containsExactly("2");
  }

  @Test
  void officialBlankRemainsUnknownAndCannotProduceAnImprintBadge() {
    var match = PillSimilarity.rank(clues("A", "B"), List.of(row("1", "A", ""))).getFirst();
    assertThat(match.printMatched()).isNull();
    assertThat(match.matchDetails()).filteredOn(d -> d.field().equals("printBack")).singleElement()
        .satisfies(d -> assertThat(d.result()).isEqualTo("UNKNOWN"));
    assertThat(PillSimilarity.rank(clues("A", null), List.of(row("1", "", "")))).isEmpty();
  }

  @Test
  void noImprintAndUnreadableHaveDifferentContradictionBehavior() {
    var none = new PillIdentification(null, null, "A", null, "원형", "흰색", null, null, null,
        READABLE, NONE, UNREADABLE, UNREADABLE);
    var target = row("1", "A", "B");
    assertThat(PillSimilarity.rank(none, List.of(target))).isEmpty();
    assertThat(PillSimilarity.rank(clues("A", null), List.of(target))).hasSize(1);
    assertThat(clues("A", "").printBackState()).isEqualTo(UNREADABLE);
  }

  @Test
  void confirmedLineConflictRejectsWhileUnknownLineDoesNotBecomeMatch() {
    var target = row("1", "A", "B");
    var plus = (tools.jackson.databind.node.ObjectNode) target.deepCopy(); plus.put("LINE_FRONT", "+");
    var minus = new PillIdentification(null, null, "A", "B", "원형", "하양", null, "-", null);
    assertThat(PillSimilarity.rank(minus, List.of(plus))).isEmpty();
    assertThat(PillSimilarity.rank(minus, List.of(target)).getFirst().exactLines()).isZero();
    plus.put("LINE_FRONT", "-");
    assertThat(PillSimilarity.rank(minus, List.of(target, plus))).extracting(PillSimilarity.Match::exactLines)
        .containsExactly(1, 0);
  }

  @Test
  void allObservedColorsMustBelongToTheOfficialPill() {
    var c = new PillIdentification(null, null, "A", null, "원형", "하양", "노랑", null, null);
    var white = row("1", "A", "B");
    var both = (tools.jackson.databind.node.ObjectNode) white.deepCopy(); both.put("COLOR_CLASS2", "노랑");
    assertThat(PillSimilarity.rank(c, List.of(white, both))).singleElement().satisfies(m -> assertThat(m.item()).isEqualTo(both));
  }

  @Test
  void partialSymbolsAndLogoCannotSatisfyTheRequiredImprint() {
    assertThat(clues("A?", null).printFrontState()).isEqualTo(UNREADABLE);
    assertThat(clues("□", null).printFront()).isNull();
    assertThat(clues("+", null).printFrontState()).isEqualTo(UNREADABLE);
    assertThatThrownBy(() -> new PillIdentification(null, null, "A", null, "원형", "하양", null, null, null,
        NONE, UNREADABLE, UNREADABLE, UNREADABLE)).isInstanceOf(BusinessException.class);
  }

  PillIdentification clues(String front, String back) {
    return new PillIdentification(null, null, front, back, "원형", "백색", null, null, null);
  }
  JsonNode row(String code, String front, String back) {
    return json.valueToTree(Map.of("ITEM_SEQ", code, "ITEM_NAME", "알약", "PRINT_FRONT", front,
        "PRINT_BACK", back, "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양"));
  }
}
