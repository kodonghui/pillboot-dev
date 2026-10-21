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
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import tools.jackson.databind.json.JsonMapper;

class PhotoCandidateLookupTests {
  final PublicDataClient client = mock(PublicDataClient.class);
  final PillCatalog catalog = mock(PillCatalog.class);
  final PhotoCandidateLookup lookup = new PhotoCandidateLookup(client, catalog);
  final JsonMapper json = new JsonMapper();

  @Test
  void permitListUsesRealSpecialtyFieldForBothDrugTypes() {
    var otc = row("900001", "합성일반약");
    otc.remove("ETC_OTC_NAME"); otc.put("SPCLTY_PBLC", "일반의약품");
    var etc = row("900002", "합성전문약");
    etc.remove("ETC_OTC_NAME"); etc.put("SPCLTY_PBLC", "전문의약품");
    when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(otc, etc))));
    var result = lookup.prescriptionResult("합성", null);
    assertThat(result.status()).isEqualTo("OK");
    assertThat(result.candidates()).extracting(value -> value.etcOtc()).containsExactly("OTC", "ETC");
  }

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
  void imprintOnlyUsesCompleteCatalogWithoutNameOrMakerQuery() {
    var imprintOnly = new PillIdentification("null", null, "DK", "10", "원형", "흰색", null, null, null);
    var target = row("197400040", "인데놀정10mg(프로프라놀롤염산염)");
    target.putAll(Map.of("PRINT_FRONT", "DK", "PRINT_BACK", "10", "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양"));
    var unrelated = row("900001", "비슷한외형의다른약");
    unrelated.putAll(Map.of("PRINT_FRONT", "AB", "PRINT_BACK", "40", "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양"));
    when(catalog.rows()).thenReturn(List.of(json.valueToTree(unrelated), json.valueToTree(target)));
    var result = lookup.pill(imprintOnly);
    assertThat(result).singleElement().satisfies(candidate -> {
      assertThat(candidate.itemSeq()).isEqualTo("197400040");
      assertThat(candidate.printMatched()).isTrue();
    });
    verifyNoInteractions(client);
    verify(catalog).rows();
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
    for (var row : List.of(valid, invalid)) row.putAll(Map.of("PRINT_FRONT", "AB", "DRUG_SHAPE", "원형", "COLOR_CLASS1", "하양"));
    when(catalog.rows()).thenReturn(List.of(json.valueToTree(invalid), json.valueToTree(valid)));
    var pill = lookup.pillResult(new PillIdentification("합성약", null, "AB", null, "원형", "하양", null, null, null));
    assertThat(pill.status()).isEqualTo("OK");
    assertThat(pill.lookupStatus()).isEqualTo("PARTIAL");
    assertThat(pill.candidates()).hasSize(1);
  }

  @Test
  void duplicateRowsPreferDirectOrientationAndOnlyValidMetadataConsumesTheLimit() {
    var direct = row("900001", "합성약"); direct.putAll(Map.of("PRINT_FRONT", "A", "PRINT_BACK", "B", "DRUG_SHAPE", "원형", "COLOR_CLASS1", "흰색"));
    var swapped = new LinkedHashMap<>(direct); swapped.put("PRINT_FRONT", "B"); swapped.put("PRINT_BACK", "A");
    var malformed = new LinkedHashMap<>(direct); malformed.put("ITEM_SEQ", "900002"); malformed.remove("ETC_OTC_NAME");
    when(catalog.rows()).thenReturn(List.of(json.valueToTree(swapped), json.valueToTree(malformed), json.valueToTree(direct)));
    var result = lookup.pillResult(new PillIdentification(null, null, "A", "B", "원형", "흰색", null, null, null));
    assertThat(result.totalCandidateCount()).isEqualTo(1);
    assertThat(result.excludedCount()).isEqualTo(1);
    assertThat(result.candidates()).singleElement().satisfies(c -> {
      assertThat(c.orientation()).isEqualTo("DIRECT"); assertThat(c.evidenceRank()).isEqualTo(1);
    });
  }

  static Map<String, Object> row(String code, String name) {
    return new LinkedHashMap<>(
        Map.of("ITEM_SEQ", code, "ITEM_NAME", name, "ENTP_NAME", "합성제약", "ETC_OTC_NAME", "일반의약품"));
  }
}
