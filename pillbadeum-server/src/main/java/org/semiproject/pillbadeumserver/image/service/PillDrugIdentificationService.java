package org.semiproject.pillbadeumserver.image.service;

import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.image.domain.*;
import org.springframework.stereotype.Service;

/** REG-007: 기존 배열 API도 같은 필수 검사·공식 비교기를 사용하며 숫자 점수는 폐기함. */
@Service
public class PillDrugIdentificationService {
  private final PhotoCandidateLookup lookup;
  public PillDrugIdentificationService(PublicDataClient publicData, PillCatalog catalog) {
    this.lookup = new PhotoCandidateLookup(publicData, catalog);
  }

  public List<PillIdentificationCandidate> identify(PillIdentification input) {
    var result = lookup.pillResult(input);
    if (!result.missingFields().isEmpty())
      throw new BusinessException(ErrorCode.INVALID_INPUT, "읽힌 각인 한 면·모양·색상 한 가지가 필요합니다. 다시 찍거나 직접 입력해 주세요.");
    var values = new ArrayList<PillIdentificationCandidate>();
    for (var c : result.candidates()) values.add(new PillIdentificationCandidate(values.size() + 1,
        null, c.itemSeq(), c.name(), c.maker(), c.imageUrl(), c.printFront(), c.printBack(),
        c.shape(), c.color(), null, c.lineFront(), c.lineBack(),
        "OTC".equals(c.etcOtc()) ? "일반의약품" : "전문의약품", c.className(),
        c.material(), null, null, null, null, null, null, null, null,
        c.orientation(), c.evidenceRank(), c.matchDetails()));
    return List.copyOf(values);
  }

  public void requireCatalogReady() { lookup.requirePillCatalog(); }
}
