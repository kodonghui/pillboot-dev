package org.semiproject.pillbadeumserver.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.service.PhotoCandidateLookup;
import tools.jackson.databind.json.JsonMapper;

class PhotoCandidateLookupTests {
  final PublicDataClient client = mock(PublicDataClient.class);
  final PhotoCandidateLookup lookup = new PhotoCandidateLookup(client);
  final JsonMapper json = new JsonMapper();

  @Test
  void exactNameComesFirstAndDuplicateCodesDoNotConsumeFiveCandidateLimit() {
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 1; i <= 7; i++) rows.add(row("90000" + i, i == 6 ? "정확한약" : "비슷한약"));
    rows.addFirst(row("900001", "비슷한약"));
    when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", rows)));
    var result = lookup.prescription("정확한약", "합성제약");
    assertThat(result).hasSize(5);
    assertThat(result.getFirst().itemSeq()).isEqualTo("900006");
    assertThat(result.stream().map(c -> c.itemSeq())).doesNotHaveDuplicates();
    verify(client)
        .get(
            DrugSyncService.PERMIT_INFO_PATH,
            Map.of("item_name", "정확한약", "entp_name", "합성제약", "pageNo", 1, "numOfRows", 100));
  }

  @Test
  void unknownExternalAttributesAreExplicitButDrugTypeIsNeverAssumedOtc() {
    var row = row("900001", "합성약");
    when(client.get(anyString(), anyMap()))
        .thenAnswer(invocation -> json.valueToTree(Map.of("items", List.of(row))));
    var result = lookup.prescription("합성약", null).getFirst();
    assertThat(result.shape()).isEqualTo("미확인");
    assertThat(result.material()).isEqualTo("미확인");
    row.remove("ETC_OTC_NAME");
    assertThatThrownBy(() -> lookup.prescription("합성약", null))
        .isInstanceOf(ExternalApiException.class);
  }

  @Test
  void imprintOnlyCannotStartCatalogQueryAndVisualFieldsAreOnlyRankingClues() {
    var imprintOnly = new PillIdentification(null, null, "A", "B", "원형", "흰색", null, null, null);
    assertThatThrownBy(() -> lookup.pill(imprintOnly)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client);
    when(client.get(anyString(), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of())));
    assertThat(
            lookup.pill(
                new PillIdentification("합성약", "합성제약", "A", "B", "원형", "흰색", null, null, null)))
        .isEmpty();
    verify(client)
        .get(
            DrugSyncService.GRAIN_INFO_PATH,
            Map.of("item_name", "합성약", "entp_name", "합성제약", "pageNo", 1, "numOfRows", 100));
  }

  @Test
  void incompleteCandidateDoesNotRemoveValidAlternativesOrBecomeOtc() {
    var valid = row("900001", "합성약");
    var invalid = row("900002", "합성약"); invalid.remove("ETC_OTC_NAME");
    when(client.get(anyString(), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of(invalid, valid))));
    var result = lookup.prescriptionResult("합성약", null);
    assertThat(result.status()).isEqualTo("PARTIAL");
    assertThat(result.candidates()).singleElement().satisfies(value -> assertThat(value.itemSeq()).isEqualTo("900001"));
    assertThat(result.excludedCount()).isEqualTo(1);
    var pill = lookup.pillResult(new PillIdentification("합성약", null, null, null, null, null, null, null, null));
    assertThat(pill.status()).isEqualTo("PARTIAL");
    assertThat(pill.candidates()).hasSize(1);
  }

  static Map<String, Object> row(String code, String name) {
    return new LinkedHashMap<>(
        Map.of("ITEM_SEQ", code, "ITEM_NAME", name, "ENTP_NAME", "합성제약", "ETC_OTC_NAME", "일반의약품"));
  }
}
