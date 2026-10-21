package org.semiproject.pillbadeumserver.image.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;

/** REG-005: 복용 일정을 추정하지 않고 선택한 공식 코드만 저장한다. */
public record PhotoDrugSelectionRequest(DrugSource source, Long photoId, List<Item> items,
                                        String identificationId) {
  public PhotoDrugSelectionRequest(DrugSource source, Long photoId, List<Item> items) {
    this(source, photoId, items, null);
  }
  public record Item(String itemSeq) {}

  public record Saved(long myDrugId, String itemSeq, String name) {}

  public record Skipped(long myDrugId, String itemSeq, String name, String reason) {}

  @Schema(name = "PhotoDrugSelectionResult")
  public record Result(List<Saved> saved, List<Skipped> skipped) {}
}
