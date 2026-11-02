package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.drug.service.PrescriptionDrugLookup;
import tools.jackson.databind.json.JsonMapper;

/** REG-003: 검색 성공 수뿐 아니라 다른 함량·제형의 잘못된 후보가 제외되는지 검증함. */
class PrescriptionDrugLookupTests {
    final PublicDataClient client = mock(PublicDataClient.class);
    final PrescriptionDrugLookup lookup = new PrescriptionDrugLookup(client);
    final JsonMapper json = new JsonMapper();

    @Test
    void broadQueryStillKeepsStrengthAndStopsAfterThreeVariants() {
        var queries = new ArrayList<String>();
        when(client.get(anyString(), anyMap())).thenAnswer(call -> {
            Map<String, ?> params = call.getArgument(1);
            String query = (String) params.get("item_name");
            queries.add(query);
            assertThat(params.get("numOfRows")).isEqualTo(100);
            assertThat(params.get("entp_name")).isEqualTo("보령");
            return rows(query.equals("카나브정") ? List.of("카나브정30밀리그램", "카나브정60밀리그램(성분)") : List.of());
        });

        var result = lookup.find("카나브정 60mg/1정", "보령");

        assertThat(queries).containsExactly("카나브정60mg", "카나브정60밀리그램", "카나브정");
        assertThat(result).singleElement().satisfies(row -> assertThat(row.path("ITEM_NAME").asString()).contains("60밀리그램"));
    }

    @Test
    void originalMgSpellingCanSucceedWithoutReplacingItOrBroadeningTheQuery() {
        when(client.get(anyString(), anyMap())).thenReturn(rows(List.of("명문아스피린장용정100mg")));
        assertThat(lookup.find("명문아스피린장용정 100mg/1정", null)).hasSize(1);
        verify(client).get(anyString(), eq(Map.of("item_name", "명문아스피린장용정100mg", "pageNo", 1, "numOfRows", 100)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "합성정0.5mg | 합성정500마이크로그램 | true",
        "합성정0.5mg | 합성정500mg | false",
        "합성정1g | 합성정1000밀리그램 | true",
        "타이레놀정500mg | 타이레놀정500밀리그람(아세트아미노펜) | true",
        "타이레놀정500밀리그램 | 타이레놀정500밀리그람(아세트아미노펜) | true",
        "타이레놀정500밀리그람 | 타이레놀정500밀리그램 | true",
        "타이레놀정500mg | 타이레놀정160밀리그람 | false",
        "합성정0.5그람 | 합성정500mg | true",
        "합성정500마이크로그람 | 합성정0.5밀리그람 | true",
        "합성액5밀리그람/5mL | 합성액5mg/5mL | true",
        "합성액5밀리그람/5mL | 합성액1mg/mL | false",
        "합성정0.25mg | 합성정0.5mg | false",
        "합성정60mg | 합성정30밀리그램 | false",
        "합성정60mg | 다른정60밀리그램 | false",
        "합성정60mg | 합성캡슐60mg | false",
        "합성정60mg | 합성서방정60mg | false",
        "합성장용정60mg | 합성정60mg | false",
        "합성서방캡슐180mg/1캡슐 | 합성서방캡슐180밀리그램(성분) | true",
        "합성서방캡슐180mg | 합성캡슐180mg | false",
        "합성정5/10mg | 합성정5mg/10mg | true",
        "합성정5/10mg | 합성정10mg/5mg | false",
        "합성정5/10mg | 합성정10mg | false",
        "합성액5mg/5mL | 합성액1mg/mL | false",
        "합성액5mg/5mL | 합성액5밀리그램/5mL | true",
        "합성정5mg | 합성액5mg/mL | false",
        "합성5000IU | 합성5000IU/mL | false",
        "합성액100mg/5밀리리터 | 합성액100mg/5mL | true",
        "합성액100밀리그램/밀리리터 | 합성액100mg/mL | true",
        "합성액100밀리그램/밀리리터 | 합성액100mg | false",
        "합성액100mg | 합성액100밀리그램/밀리리터 | false"
    })
    void comparesProductStrengthAndFormWithoutDoseGuessing(String input, String candidate, boolean accepted) {
        when(client.get(anyString(), anyMap())).thenReturn(rows(List.of(candidate)));
        assertThat(lookup.find(input.strip(), null)).hasSize(accepted ? 1 : 0);
        verify(client, atMost(3)).get(anyString(), anyMap());
    }

    @Test
    void missingStrengthIsNotFilledWithAnAssumedDose() {
        when(client.get(anyString(), anyMap())).thenReturn(rows(List.of("합성정30밀리그램", "합성정60밀리그램")));
        assertThat(lookup.find("합성정", null)).hasSize(2);
        verify(client, times(1)).get(anyString(), anyMap());
    }

    @Test
    void externalFailureIsNotConvertedIntoNoMatchOrRetriedAsAnotherName() {
        var failure = ExternalApiException.failed("공공데이터", "시험 실패", null);
        when(client.get(anyString(), anyMap())).thenThrow(failure);
        assertThatThrownBy(() -> lookup.find("합성정60mg", null)).isSameAs(failure);
        verify(client, times(1)).get(anyString(), anyMap());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "합성정60mg... | 합성정60밀리그램(성분)",
        "합성정60mg… | 합성정60밀리그램(성분)",
        "합성정60mg(성분... | 합성정60밀리그램(성분)",
        "합성정60mg(성분 | 합성정60밀리그램(성분)",
        "합성정0.5mg... | 합성정500마이크로그램",
        "합성정0.25/1000mg… | 합성정0.25mg/1000mg",
        "합성서방정60mg... | 합성서방정60밀리그램"
    })
    void clippedDescriptionDoesNotDiscardAnOtherwiseExactProduct(String input, String candidate) {
        when(client.get(anyString(), anyMap())).thenReturn(rows(List.of(candidate, "다른정60mg", "합성정30mg", "합성캡슐60mg")));
        assertThat(lookup.find(input, null)).singleElement()
                .satisfies(row -> assertThat(row.path("ITEM_NAME").asString()).isEqualTo(candidate));
        verify(client).get(anyString(), argThat(params -> !params.get("item_name").toString().contains("...")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"합성정0.5m…", "합성서방...", "합성정5mg/..."})
    void incompleteUnitOrDosageFormRequiresNameSupplementInsteadOfGuessing(String input) {
        assertThat(lookup.find(input, null)).isEmpty();
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "슈가메트서방정5/100... | 슈가메트서방정5/100 | 슈가메트서방정5/1000밀리그램",
        "듀비메트서방정0.5/10... | 듀비메트서방정0.5/10 | 듀비메트서방정0.5/1000밀리그램",
        "듀비메트서방정0.5/10… | 듀비메트서방정0.5/10 | 듀비메트서방정0.5/1000밀리그램",
        "듀비메트서방정0.5/10⋯ | 듀비메트서방정0.5/10 | 듀비메트서방정0.5/1000밀리그램",
        "듀비메트서방정0.5/10‥ | 듀비메트서방정0.5/10 | 듀비메트서방정0.5/1000밀리그램",
        "합성정60... | 합성정60 | 합성정60밀리그램"
    })
    void clippedStrengthSearchesOnlyTheReadPrefixAndKeepsTheDecimal(String input, String keyword, String candidate) {
        when(client.get(anyString(), anyMap())).thenReturn(rows(List.of(candidate,
                "다른정60mg", "듀비메트서방정0.25/1000밀리그램", "슈가메트정5/1000밀리그램")));
        assertThat(lookup.find(input, null)).singleElement()
                .satisfies(row -> assertThat(row.path("ITEM_NAME").asString()).isEqualTo(candidate));
        verify(client).get(anyString(), eq(Map.of("item_name", keyword, "pageNo", 1, "numOfRows", 100)));
    }

    @Test
    void clippedNumericPrefixKeepsMultipleOfficialChoicesWithoutInventingTheMissingDigits() {
        when(client.get(anyString(), anyMap())).thenReturn(rows(List.of(
                "합성정60밀리그램", "합성정600밀리그램", "합성정6밀리그램", "합성서방정60밀리그램")));
        assertThat(lookup.find("합성정60...", null)).extracting(row -> row.path("ITEM_NAME").asString())
                .containsExactly("합성정60밀리그램", "합성정600밀리그램");
        verify(client).get(anyString(), eq(Map.of("item_name", "합성정60", "pageNo", 1, "numOfRows", 100)));
    }

    @Test
    void clippedNameWithoutStrengthKeepsAllExactProductStrengthOptions() {
        when(client.get(anyString(), anyMap())).thenReturn(rows(List.of("합성정30mg", "합성정60mg", "다른정60mg", "합성서방정60mg")));
        assertThat(lookup.find("합성정...", null)).hasSize(2);
    }

    @Test
    void unsupportedConcentrationNeverFallsBackToTheBareProductName() {
        var queries = new ArrayList<String>();
        when(client.get(anyString(), anyMap())).thenAnswer(call -> {
            Map<String, ?> params = call.getArgument(1);
            queries.add((String) params.get("item_name"));
            return rows(List.of());
        });
        assertThat(lookup.find("합성액5mg/5mL", null)).isEmpty();
        assertThat(queries).containsExactly("합성액5mg/5mL", "합성액5밀리그램/5mL");
    }

    // ---- V3-REG-002 처방전 후보 함량 상태(REG-003 변경) ----

    @Test
    void readStrengthKeepsTheSameProductWithOtherStrengthsAsDifferent() {
        var queries = recordQueries(Map.of("합성정", List.of("합성정10mg", "합성정20밀리그램(성분)", "합성서방정20mg",
                "합성캡슐20mg", "합성정", "합성정20mg/5mL", "다른합성정20mg")));

        var found = lookup.findWithStrength("합성정 20mg", null);

        assertThat(queries).containsExactly("합성정");
        assertThat(found).extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength()).containsExactly(
                "합성정10mg=DIFFERENT", "합성정20밀리그램(성분)=MATCH", "합성정=UNKNOWN");
    }

    @Test
    void compoundStrengthMatchesOnlyTheSameIngredientAmountsInOrder() {
        recordQueries(Map.of("합성정", List.of("합성정500/125밀리그램", "합성정500/250mg", "합성정125/500mg")));

        assertThat(lookup.findWithStrength("합성정500/125mg", null))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성정500/125밀리그램=MATCH", "합성정500/250mg=DIFFERENT", "합성정125/500mg=DIFFERENT");
    }

    @Test
    void readNameWithoutStrengthKeepsTheExistingCandidatesAsUnknown() {
        var queries = recordQueries(Map.of("합성정", List.of("합성정30밀리그램", "합성정60밀리그램", "합성서방정60mg")));

        assertThat(lookup.findWithStrength("합성정", null))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성정30밀리그램=UNKNOWN", "합성정60밀리그램=UNKNOWN");
        assertThat(queries).containsExactly("합성정");
    }

    @Test
    void concentrationAndClippedNamesKeepTheExistingComparison() {
        var queries = recordQueries(Map.of("합성액5mg/5mL", List.of("합성액5밀리그램/5mL", "합성액1mg/mL"),
                "합성정60", List.of("합성정60밀리그램", "합성정600밀리그램", "합성정6밀리그램")));

        assertThat(lookup.findWithStrength("합성액5mg/5mL", null))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성액5밀리그램/5mL=MATCH");
        // 숫자가 잘린 이름은 함량을 확인할 수 없어 일치로 보지 않음
        assertThat(lookup.findWithStrength("합성정60...", null))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성정60밀리그램=UNKNOWN", "합성정600밀리그램=UNKNOWN");
        assertThat(queries).containsExactly("합성액5mg/5mL", "합성정60");
    }

    @Test
    void emptyBaseResultFallsBackToTheReadSpelling() {
        var queries = recordQueries(Map.of("합성캅셀60mg", List.of(), "합성캅셀60밀리그램", List.of("합성캅셀60밀리그램")));

        assertThat(lookup.findWithStrength("합성캅셀60mg", "보령"))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성캅셀60밀리그램=MATCH");
        assertThat(queries).containsExactly("합성캅셀", "합성캅셀60mg", "합성캅셀60밀리그램");
    }

    @Test
    void clippedBaseResultWithoutAMatchLooksUpTheExactProductOnce() {
        var queries = new ArrayList<String>();
        when(client.get(anyString(), anyMap())).thenAnswer(call -> {
            Map<String, ?> params = call.getArgument(1);
            String query = (String) params.get("item_name");
            queries.add(query);
            if (query.equals("합성정")) return rows(List.of("합성정10mg"), 250);
            return rows(query.equals("합성정20mg") ? List.of("합성정20mg") : List.of());
        });

        assertThat(lookup.findWithStrength("합성정20mg", null))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성정10mg=DIFFERENT", "합성정20mg=MATCH");
        assertThat(queries).containsExactly("합성정", "합성정20mg");
    }

    @Test
    void completeBaseResultWithoutAMatchStillLooksUpTheReadSpelling() {
        // 함량이 이름 가운데 있으면 함량 뗀 이름('합성정')으로는 실제 품목 '합성20mg정'이 나오지 않음
        var queries = recordQueries(Map.of("합성정", List.of("합성정10mg"), "합성20mg정", List.of("합성20mg정")));

        assertThat(lookup.findWithStrength("합성20mg정", null))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성정10mg=DIFFERENT", "합성20mg정=MATCH");
        assertThat(queries).containsExactly("합성정", "합성20mg정");
    }

    @Test
    void clippedBaseResultWithOneMatchStillCollectsTheOtherMatchingProducts() {
        // 첫 페이지에 일치 품목이 하나뿐이어도 뒤쪽 페이지에 같은 함량 품목이 더 있을 수 있음(자동 선택은 유일할 때만)
        var queries = new ArrayList<String>();
        when(client.get(anyString(), anyMap())).thenAnswer(call -> {
            Map<String, ?> params = call.getArgument(1);
            String query = (String) params.get("item_name");
            queries.add(query);
            if (query.equals("합성정")) return rows(List.of("합성정10mg", "합성정20mg"), 250);
            return rows(query.equals("합성정20mg") ? List.of("합성정20mg", "합성정20밀리그램(성분)") : List.of());
        });

        assertThat(lookup.findWithStrength("합성정20mg", null))
                .extracting(c -> c.row().path("ITEM_NAME").asString() + "=" + c.strength())
                .containsExactly("합성정10mg=DIFFERENT", "합성정20mg=MATCH", "합성정20밀리그램(성분)=MATCH");
        assertThat(queries).containsExactly("합성정", "합성정20mg");
    }

    @Test
    void publicDataFailureDuringStrengthLookupIsNotHiddenAsNoCandidates() {
        var failure = ExternalApiException.failed("공공데이터", "시험 실패", null);
        when(client.get(anyString(), anyMap())).thenThrow(failure);
        assertThatThrownBy(() -> lookup.findWithStrength("합성정20mg", null)).isSameAs(failure);
        verify(client, times(1)).get(anyString(), anyMap());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "합성정20mg       | 합성정20밀리그램     | MATCH",
        "합성정20mg       | 합성정10mg         | DIFFERENT",
        "합성정20mg       | 합성정             | UNKNOWN",
        "합성정20mg       | 합성서방정20mg      | UNKNOWN",
        "합성정          | 합성정10mg         | UNKNOWN",
        "합성정60...      | 합성정60밀리그램     | UNKNOWN",
        "합성액5mg/5mL    | 합성액5밀리그램/5mL  | MATCH",
    })
    void storedReadNameAndCandidateNameGiveTheSameStateAsTheLookup(String readName, String candidate, StrengthMatch expected) {
        assertThat(PrescriptionDrugLookup.strengthMatch(readName, candidate)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "합성정20mg | 합성정20밀리그램 | MATCH",
        "합성정20mg | 합성정10밀리그램 | DIFFERENT",
        "합성정20mg | 합성서방정20밀리그램 | UNKNOWN",
    })
    void candidateNameClippedInsideItsIngredientDescriptionKeepsTheLookupState(String readName, String product, StrengthMatch expected) {
        // 후보 이름은 50자로 잘려 저장되고(PhotoCandidateLookup) 다시 보기는 잘린 이름으로 대조함
        String full = product + "(아주긴성분이름염산염수화물과또다른성분이름의복합체를설명하는아주긴괄호안의글자가오십자를넘어감)";
        String stored = full.substring(0, full.offsetByCodePoints(0, 50));
        assertThat(stored).doesNotContain(")");
        assertThat(PrescriptionDrugLookup.strengthMatch(readName, full)).isEqualTo(expected);
        assertThat(PrescriptionDrugLookup.strengthMatch(readName, stored)).isEqualTo(expected);
    }

    private List<String> recordQueries(Map<String, List<String>> results) {
        var queries = new ArrayList<String>();
        when(client.get(anyString(), anyMap())).thenAnswer(call -> {
            Map<String, ?> params = call.getArgument(1);
            String query = (String) params.get("item_name");
            queries.add(query);
            return rows(results.getOrDefault(query, List.of()));
        });
        return queries;
    }

    private tools.jackson.databind.JsonNode rows(List<String> names, int totalCount) {
        var items = new ArrayList<Map<String, String>>();
        for (int i = 0; i < names.size(); i++) items.add(Map.of("ITEM_SEQ", seq(names.get(i)), "ITEM_NAME", names.get(i)));
        return json.valueToTree(Map.of("items", items, "totalCount", totalCount));
    }

    // 같은 품목은 어느 조회에서 나와도 같은 품목기준코드를 가짐
    private static String seq(String name) {
        return "9" + Math.floorMod(name.hashCode(), 100_000_000);
    }

    private tools.jackson.databind.JsonNode rows(List<String> names) {
        var items = new ArrayList<Map<String, String>>();
        for (int i = 0; i < names.size(); i++) items.add(Map.of("ITEM_SEQ", seq(names.get(i)), "ITEM_NAME", names.get(i)));
        return json.valueToTree(Map.of("items", items));
    }
}
