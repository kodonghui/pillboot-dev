package org.semiproject.pillbadeumserver.drug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.DurWarningRepository;
import org.semiproject.pillbadeumserver.drug.dto.DrugGuideResponse;
import org.semiproject.pillbadeumserver.drug.service.DrugGuideService;
import tools.jackson.databind.json.JsonMapper;

/** 약 근거 화면: 공식 본문이 길어도 그 항목이 안내 없이 사라지지 않는지 확인함. */
class DrugGuideSectionTests {
  final PublicDataClient client = mock(PublicDataClient.class);
  final DrugRepository drugs = mock(DrugRepository.class);
  final DurWarningRepository durWarnings = mock(DurWarningRepository.class);
  final DrugGuideService service = new DrugGuideService(client, drugs, durWarnings);
  final JsonMapper json = new JsonMapper();

  @Test
  void longOfficialPrecautionIsShownAsOmittedInsteadOfDisappearing() {
    var drug = DrugWebTests.drug();
    when(drugs.findById(drug.getItemSeq())).thenReturn(Optional.of(drug));
    when(durWarnings.findAllByDrug_ItemSeqOrderByDurWarningIdAsc(drug.getItemSeq())).thenReturn(List.of());
    when(client.get(eq(DrugGuideService.EASY_DRUG_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of())));
    var permit = Map.of("ITEM_SEQ", drug.getItemSeq(), "ITEM_NAME", drug.getName(),
        "EE_DOC_DATA", "<DOC><P>두통, 치통의 완화</P></DOC>",
        "NB_DOC_DATA", "<DOC><P>" + "다음 환자에는 투여하지 말 것. ".repeat(1_500) + "</P></DOC>");
    when(client.get(eq(DrugGuideService.PERMIT_DETAIL_PATH), anyMap()))
        .thenReturn(json.valueToTree(Map.of("items", List.of(permit))));

    var guide = service.getGuide(drug.getItemSeq());

    assertThat(guide.sections()).extracting(DrugGuideResponse.Section::key).containsExactly("EFFICACY", "PRECAUTION");
    var precaution = guide.sections().get(1);
    assertThat(precaution.title()).isEqualTo("사용상의 주의사항");
    assertThat(precaution.content()).contains("의약품안전나라").hasSizeLessThan(200);
  }
}
