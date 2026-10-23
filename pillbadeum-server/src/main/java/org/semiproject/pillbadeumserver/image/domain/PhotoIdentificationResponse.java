package org.semiproject.pillbadeumserver.image.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;

/** REG-003/004/007: 처방전 외피를 유지하고 낱알 결과에만 v1 상세 필드를 추가함. */
public record PhotoIdentificationResponse(
    ImagePhotoKind kind, Long photoId, List<Long> photoIds, String status,
    PillIdentification clues, Boolean printMatched, List<DrugGroup> drugs, List<Candidate> candidates,
    @JsonUnwrapped @JsonInclude(JsonInclude.Include.NON_NULL) PillIdentificationDetails details) {

  public PhotoIdentificationResponse(ImagePhotoKind kind, Long photoId, List<Long> photoIds,
      String status, PillIdentification clues, Boolean printMatched,
      List<DrugGroup> drugs, List<Candidate> candidates) {
    this(kind, photoId, photoIds, status, clues, printMatched, drugs, candidates, null);
  }
  public PhotoIdentificationResponse withDetails(PillIdentificationDetails details) {
    return new PhotoIdentificationResponse(kind, photoId, photoIds, status, clues, printMatched, drugs, candidates, details);
  }

  /** REG-007: JsonNode로 반환하는 종료 상태의 문서 모델임. 실제 응답 생성·필드는 바꾸지 않음. */
  @Schema(name = "PillIdentificationTerminal", description = "종료된 식별 결과. REPLACED는 새 ID, CONSUMED는 최초 등록 영수증을 반환함.")
  public record Terminal(
      int contractVersion, String identificationId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"REPLACED", "CANCELLED", "CONSUMED"}) String lifecycle,
      String origin, DrugSource registrationSource, OffsetDateTime expiresAt, List<String> nextActions,
      @JsonInclude(JsonInclude.Include.NON_NULL) String replacementIdentificationId,
      @JsonInclude(JsonInclude.Include.NON_NULL) RegistrationReceipt registrationReceipt) {}

  @Schema(name = "PillRegistrationReceipt", description = "최초 등록의 HTTP 상태와 saved/skipped 본문. 재전송 시 같은 영수증을 사용함.")
  public record RegistrationReceipt(int httpStatus, PhotoDrugSelectionRequest.Result body) {}

  public record DrugGroup(int readNo, String readName, String lookup, List<Candidate> candidates) {}
  public record Candidate(
      String itemSeq, String name, String maker, String etcOtc, String imageUrl,
      Boolean cancelled, boolean alreadyRegistered, Boolean printMatched,
      String printFront, String printBack, String shape, String color,
      @JsonInclude(JsonInclude.Include.NON_NULL) String orientation,
      @JsonInclude(JsonInclude.Include.NON_NULL) Integer evidenceRank,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<PillMatchDetail> matchDetails,
      String ingredientName) {
    public Candidate(String itemSeq, String name, String maker, String etcOtc, String imageUrl,
        Boolean cancelled, boolean alreadyRegistered, Boolean printMatched,
        String printFront, String printBack, String shape, String color,
        String orientation, Integer evidenceRank, List<PillMatchDetail> matchDetails) {
      this(itemSeq, name, maker, etcOtc, imageUrl, cancelled, alreadyRegistered, printMatched,
          printFront, printBack, shape, color, orientation, evidenceRank, matchDetails, null);
    }
    public Candidate(String itemSeq, String name, String maker, String etcOtc, String imageUrl,
        Boolean cancelled, boolean alreadyRegistered, Boolean printMatched,
        String printFront, String printBack, String shape, String color, String ingredientName) {
      this(itemSeq, name, maker, etcOtc, imageUrl, cancelled, alreadyRegistered, printMatched,
          printFront, printBack, shape, color, null, null, null, ingredientName);
    }
    public Candidate(String itemSeq, String name, String maker, String etcOtc, String imageUrl,
        Boolean cancelled, boolean alreadyRegistered, Boolean printMatched,
        String printFront, String printBack, String shape, String color) {
      this(itemSeq, name, maker, etcOtc, imageUrl, cancelled, alreadyRegistered, printMatched,
          printFront, printBack, shape, color, null, null, null);
    }
  }
}
