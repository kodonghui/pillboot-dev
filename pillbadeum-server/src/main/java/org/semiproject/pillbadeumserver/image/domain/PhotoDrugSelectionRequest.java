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
  /** 보완 검색은 SEARCH, 원래 사진 후보는 PRESCRIPTION_PHOTO임. 생략 시 기존 요청 출처를 사용함. */
  @Schema(name = "PhotoDrugSelectionItem")
  public record Item(String itemSeq, DrugSource source, Integer readNo) {
    public Item(String itemSeq) { this(itemSeq, null, null); }
  }

  public DrugSource sourceOf(String code) {
    return items.stream().filter(i -> i.itemSeq().strip().equals(code)).findFirst()
        .map(i -> i.source() == null ? source : i.source()).orElse(source);
  }

  public Integer readNoOf(String code) {
    return items.stream().filter(i -> i.itemSeq().strip().equals(code)).findFirst()
        .map(Item::readNo).orElse(null);
  }

  public record Saved(long myDrugId, String itemSeq, String name) {}

  public record Skipped(long myDrugId, String itemSeq, String name, String reason) {}

  @Schema(name = "PhotoDrugSelectionResult")
  public record Result(List<Saved> saved, List<Skipped> skipped) {}
}
