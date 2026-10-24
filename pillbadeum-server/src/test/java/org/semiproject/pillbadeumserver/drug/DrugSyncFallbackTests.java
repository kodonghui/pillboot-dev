package org.semiproject.pillbadeumserver.drug;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.*;
import org.semiproject.pillbadeumserver.drug.service.*;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

/** REG-008/005: 이름 검색과 선택 저장의 품목 범위를 공식 허가정보로 맞추는 검증임. */
class DrugSyncFallbackTests {
  final JsonMapper json = new JsonMapper();
  final PublicDataClient client = mock(PublicDataClient.class);
  final DrugRepository drugs = mock(DrugRepository.class);
  final DrugSyncService service = new DrugSyncService(client, drugs);

  @Test
  void permitDetailUsesRealEtcOtcCodeField() {
    var detail = new LinkedHashMap<>(row());
    detail.remove("ETC_OTC_NAME"); detail.put("ETC_OTC_CODE", "전문의약품");
    when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of())));
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(detail))));
    when(drugs.save(any())).thenAnswer(call -> call.getArgument(0));
    var result = service.getOrSync("900001");
    assertThat(result.getEtcOtc()).isEqualTo("ETC");
    assertThat(result.getMetadataSource()).isEqualTo("PERMIT");
  }

  @Test
  void permitOnlyProductIsOfficiallyConfirmedWithoutInventingVisualMetadata() {
    when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of())));
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of(row()))));
    when(drugs.save(any())).thenAnswer(c -> c.getArgument(0));
    var result = service.getOrSync("900001");
    assertThat(result.getName()).isEqualTo("합성시럽");
    assertThat(result.getShape()).isEqualTo("미확인");
    assertThat(result.getMaterial()).isEqualTo("합성성분");
  }

  @Test
  void permitMustMatchExactCodeAndMissingDrugTypeNeverDefaultsToOtc() {
    when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of())));
    var row = new LinkedHashMap<>(row()); row.put("ITEM_SEQ", "900002");
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of(row))));
    assertThatThrownBy(() -> service.getOrSync("900001")).isInstanceOf(NotFoundException.class);
    row.put("ITEM_SEQ", "900001"); row.remove("ETC_OTC_NAME");
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of(row))));
    assertThatThrownBy(() -> service.getOrSync("900001")).isInstanceOf(NotFoundException.class);
    verify(drugs, never()).save(any());
  }

  @Test
  void providerFailureIsNotDisguisedAsMissingGrainRecord() {
    when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap())).thenThrow(ExternalApiException.failed("synthetic", "unavailable", null));
    assertThatThrownBy(() -> service.getOrSync("900001")).isInstanceOf(ExternalApiException.class);
    verify(client, never()).get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap());
  }

  @Test
  void nameSearchUsesPermitRowsWithTwentyItemCapAndDoesNotWriteCache() {
    when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of(row()), "totalCount", 1)));
    var search = new DrugSearchService(client, mock(MyDrugRepository.class), mock(ProfileAccessChecker.class),
        mock(org.semiproject.pillbadeumserver.image.service.PillCatalog.class));
    assertThat(search.search("합성시럽", PageRequest.of(0, 100)).getContent()).singleElement()
        .satisfies(value -> assertThat(value.itemSeq()).isEqualTo("900001"));
    verify(client).get(DrugSyncService.PERMIT_INFO_PATH, Map.of("item_name", "합성시럽", "pageNo", 1, "numOfRows", 20));
    verifyNoInteractions(drugs);
  }

  @Test
  void searchImagesUseExactCodesAndKeepProviderPhotosAndRegisteredFlagsWithoutMoreHttp() {
    var catalog = mock(org.semiproject.pillbadeumserver.image.service.PillCatalog.class);
    var mine = mock(MyDrugRepository.class);
    var first = new LinkedHashMap<>(row());
    var second = new LinkedHashMap<>(row()); second.put("ITEM_SEQ", "900002"); second.put("ITEM_IMAGE", "https://official.test/original.jpg");
    var third = new LinkedHashMap<>(row()); third.put("ITEM_SEQ", "900003");
    when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(first, second, third), "totalCount", 3)));
    when(catalog.cachedRows()).thenReturn(List.of(
        json.valueToTree(Map.of("ITEM_SEQ", "900001", "ITEM_IMAGE", "https://official.test/matched.jpg")),
        json.valueToTree(Map.of("ITEM_SEQ", "900002", "ITEM_IMAGE", "https://official.test/other.jpg")),
        json.valueToTree(Map.of("ITEM_SEQ", "999999", "ITEM_NAME", "합성시럽", "ITEM_IMAGE", "https://official.test/wrong.jpg"))));
    when(mine.findItemSeqsByProfileId(7L)).thenReturn(List.of("900001"));
    var search = new DrugSearchService(client, mine, mock(ProfileAccessChecker.class), catalog);
    var page = search.searchForProfile(7L, "합성시럽", PageRequest.of(0, 20));
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent()).extracting(value -> value.imageUrl())
        .containsExactly("https://official.test/matched.jpg", "https://official.test/original.jpg", null);
    assertThat(page.getContent()).extracting(value -> value.registered()).containsExactly(true, false, false);
    verify(client, times(1)).get(anyString(), anyMap());
    verify(catalog, never()).rows();
    verifyNoInteractions(drugs);
  }

  @Test
  void oldIncompleteCacheIsEnrichedAndFailedRefreshKeepsOriginal() {
    var cached = Drug.create("900001", "합성시럽", "합성제약", "OTC", "미확인", null, null, null, "미확인", "미확인", null, null, "미확인", "미확인", java.time.LocalDateTime.now().minusYears(1));
    cached.recordMetadataSource("UNKNOWN", null);
    when(drugs.findById("900001")).thenReturn(Optional.of(cached));
    when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of())));
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of(row()))));
    when(drugs.save(any())).thenAnswer(c -> c.getArgument(0));
    assertThat(service.getOrSync("900001").getMaterial()).isEqualTo("합성성분");
    assertThat(cached.getMetadataSource()).isEqualTo("PERMIT");
    cached.sync(cached.getName(), cached.getMaker(), "OTC", cached.getClassName(), null, null, null, cached.getShape(), cached.getColor(), null, null, cached.getForm(), cached.getMaterial(), java.time.LocalDateTime.now().minusYears(1));
    when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap())).thenThrow(ExternalApiException.failed("synthetic", "unavailable", null));
    assertThat(service.getOrSync("900001")).isSameAs(cached);
    assertThat(cached.getMaterial()).isEqualTo("합성성분");
  }

  @Test
  void partialGrainUsesSameCodePermitMetadataWithoutReplacingAppearanceOrMutatingResponse() {
    var grain = partialGrain(null);
    var detail = new LinkedHashMap<>(row());
    detail.put("ITEM_NAME", "허가자료의 제품명 표기");
    detail.put("DRUG_SHAPE", "타원형");
    detail.put("COLOR_CLASS1", "노랑");
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(detail))));
    when(drugs.save(any())).thenAnswer(c -> c.getArgument(0));

    var result = service.getOrSync("900001");

    assertThat(result.getMaker()).isEqualTo("합성제약");
    assertThat(result.getMaterial()).isEqualTo("합성성분");
    assertThat(result.getName()).isEqualTo("합성정(합성성분)");
    assertThat(result.getShape()).isEqualTo("원형");
    assertThat(result.getColor()).isEqualTo("하양");
    assertThat(result.getPrintFront()).isEqualTo("AB");
    assertThat(result.getImageUrl()).isEqualTo("https://official.test/grain.jpg");
    assertThat(result.getMetadataSource()).isEqualTo("MIXED");
    assertThat(grain.path("ENTP_NAME").isNull()).isTrue();
    verify(client).get(DrugGuideService.PERMIT_DETAIL_PATH,
        Map.of("item_seq", "900001", "pageNo", 1, "numOfRows", 1));
  }

  @Test
  void missingIngredientIsSupplementedWithoutOverwritingPresentGrainMaker() {
    partialGrain("낱알자료제약");
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(row()))));
    when(drugs.save(any())).thenAnswer(c -> c.getArgument(0));

    var result = service.getOrSync("900001");

    assertThat(result.getMaker()).isEqualTo("낱알자료제약");
    assertThat(result.getMaterial()).isEqualTo("합성성분");
    assertThat(result.getShape()).isEqualTo("원형");
  }

  @Test
  void wrongCodeOrStillMissingRequiredPermitFieldsNeverCreatesADrug() {
    partialGrain(null);
    for (String missing : List.of("WRONG_CODE", "ENTP_NAME", "MAIN_ITEM_INGR")) {
      var detail = new LinkedHashMap<>(row());
      if (missing.equals("WRONG_CODE")) detail.put("ITEM_SEQ", "900002");
      else detail.remove(missing);
      when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap()))
          .thenReturn(json.valueToTree(Map.of("items", List.of(detail))));
      assertThatThrownBy(() -> service.getOrSync("900001")).as(missing)
          .isInstanceOf(NotFoundException.class);
    }
    verify(drugs, never()).save(any());
  }

  @Test
  void permitSupplementTimeoutRemainsAnExternalFailure() {
    partialGrain(null);
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap()))
        .thenThrow(ExternalApiException.timeout("synthetic", null));

    assertThatThrownBy(() -> service.getOrSync("900001"))
        .isInstanceOf(ExternalApiException.class);
    verify(drugs, never()).save(any());
  }

  @Test
  void completeGrainAndListMetadataDoNotAddADetailLookup() {
    partialGrain("낱알자료제약");
    when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(row()))));
    when(drugs.save(any())).thenAnswer(c -> c.getArgument(0));

    assertThat(service.getOrSync("900001").getMaker()).isEqualTo("낱알자료제약");
    verify(client, never()).get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap());
  }

  private tools.jackson.databind.JsonNode partialGrain(String maker) {
    var grain = new LinkedHashMap<String, Object>(row());
    grain.put("ITEM_NAME", "합성정(합성성분)"); grain.put("ENTP_NAME", maker);
    grain.remove("MAIN_ITEM_INGR"); grain.put("CLASS_NAME", "합성분류");
    grain.put("DRUG_SHAPE", "원형"); grain.put("COLOR_CLASS1", "하양");
    grain.put("FORM_CODE_NAME", "필름코팅정"); grain.put("PRINT_FRONT", "AB");
    grain.put("ITEM_IMAGE", "https://official.test/grain.jpg");
    var node = json.valueToTree(grain);
    when(client.get(eq(DrugSyncService.GRAIN_INFO_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(node))));
    when(client.get(eq(DrugSyncService.PERMIT_INFO_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(), "totalCount", 0)));
    return node;
  }

  static Map<String, Object> row() {
    return Map.of("ITEM_SEQ", "900001", "ITEM_NAME", "합성시럽", "ENTP_NAME", "합성제약", "ETC_OTC_NAME", "일반의약품", "MAIN_ITEM_INGR", "[SYN]합성성분");
  }
}
