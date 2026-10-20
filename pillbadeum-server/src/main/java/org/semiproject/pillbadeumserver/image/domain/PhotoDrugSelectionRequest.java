package org.semiproject.pillbadeumserver.image.domain;

import java.util.List;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;

/** REG-005: 복용 일정을 추정하지 않고 선택한 공식 코드만 저장한다. */
public record PhotoDrugSelectionRequest(DrugSource source, Long photoId, List<Item> items) {
  public record Item(String itemSeq) {}

  public record Saved(long myDrugId, String itemSeq, String name) {}

  public record Skipped(long myDrugId, String itemSeq, String name, String reason) {}

  public record Result(List<Saved> saved, List<Skipped> skipped) {}
}
