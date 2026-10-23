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
        "합성5000IU | 합성5000IU/mL | false"
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
    @org.junit.jupiter.params.provider.ValueSource(strings = {"합성정60...", "합성정0.5m…", "합성서방...", "합성정5mg/..."})
    void incompleteStrengthOrDosageFormRequiresNameSupplementInsteadOfGuessing(String input) {
        assertThat(lookup.find(input, null)).isEmpty();
        verifyNoInteractions(client);
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

    private tools.jackson.databind.JsonNode rows(List<String> names) {
        var items = new ArrayList<Map<String, String>>();
        for (int i = 0; i < names.size(); i++) items.add(Map.of("ITEM_SEQ", "90000" + i, "ITEM_NAME", names.get(i)));
        return json.valueToTree(Map.of("items", items));
    }
}
