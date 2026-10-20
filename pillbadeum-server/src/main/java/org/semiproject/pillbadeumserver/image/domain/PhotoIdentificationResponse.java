package org.semiproject.pillbadeumserver.image.domain;

import java.util.List;

/** REG-003/004/007: POST의 단서는 일시 값이고, GET은 저장된 후보와 현재 등록 여부를 반환한다. */
public record PhotoIdentificationResponse(
    ImagePhotoKind kind,
    Long photoId,
    List<Long> photoIds,
    String status,
    PillIdentification clues,
    Boolean printMatched,
    List<DrugGroup> drugs,
    List<Candidate> candidates) {
  public record DrugGroup(int readNo, String readName, String lookup, List<Candidate> candidates) {}

  public record Candidate(
      String itemSeq,
      String name,
      String maker,
      String etcOtc,
      String imageUrl,
      Boolean cancelled,
      boolean alreadyRegistered,
      Boolean printMatched) {}
}
