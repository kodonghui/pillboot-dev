package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.service.PillSimilarity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PillSimilarityTests {
    final JsonMapper json = new JsonMapper();
    @Test
    void swappedSidesAndColorSynonymsStillGiveAnExactMatchAndExcludeVisualOnlyDrugs() {
        var target = row("1", "DK", "10");
        var other = row("2", "AB", "40");
        var c = new PillIdentification("null", null, "10", "ＤＫ", "원형", "백색", "흰색", "null", null);
        var ranked = PillSimilarity.rank(c, List.of(other, target));
        assertThat(ranked).singleElement().satisfies(match -> {
            assertThat(match.item()).isEqualTo(target);
            assertThat(match.score()).isEqualTo(1);
            assertThat(PillSimilarity.printsMatch(c, target)).isTrue();
        });
    }
    @Test
    void exactTwoSidedImprintsBeatOcrApproximationAndWrongDose() {
        var exact = row("1", "A0", "10");
        var ocr = row("2", "AO", "10");
        var wrongDose = row("3", "A0", "40");
        var c = new PillIdentification(null, null, "A0", "10", "원형", "하양", null, null, null);
        assertThat(PillSimilarity.rank(c, List.of(wrongDose, ocr, exact))).extracting(m -> PillSimilarity.text(m.item(), "ITEM_SEQ"))
                .containsExactly("1", "2", "3");
    }
    @Test
    void scoreLinesRemainDistinctAndMissingInputsDoNotPenalizeCandidates() {
        var match = json.valueToTree(Map.of("ITEM_SEQ", "1", "ITEM_NAME", "알약", "LINE_FRONT", "-"));
        var c = new PillIdentification(null, "null", null, null, null, null, null, "-", null);
        assertThat(PillSimilarity.rank(c, List.of(match)).getFirst().score()).isEqualTo(1);
    }
    JsonNode row(String code, String front, String back) {
        return json.valueToTree(Map.of("ITEM_SEQ", code, "ITEM_NAME", "알약", "PRINT_FRONT", front,
                "PRINT_BACK", back, "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양"));
    }
}
