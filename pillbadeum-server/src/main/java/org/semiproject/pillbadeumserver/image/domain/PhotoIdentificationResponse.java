package org.semiproject.pillbadeumserver.image.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.DrugStrength;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;

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

  /**
   * V3-REG-002: autoSelectItemSeq는 처방전에서 함량 일치 후보가 하나이고 아직 등록되지 않았을 때만 있음.
   * 2026-11-08(처방전 줄 전체 읽기): insuranceCode는 그 줄에 인쇄된 9자리 보험코드, codeMatched는 그 코드로 찾은 품목이
   * 하나이고 읽은 이름과 맞음, similar는 이름이 꼭 맞는 품목이 없어 비슷한 이름으로 찾은 후보, unclear는 이름 글자가 흐려
   * 확실하지 않음, prescription은 1회 투약량·1일 횟수·총 일수. 모두 처방전에서 읽었을 때만 있음(없으면 응답에 칸이 없음).
   */
  public record DrugGroup(int readNo, String readName, String lookup, List<Candidate> candidates,
      @JsonInclude(JsonInclude.Include.NON_NULL) String autoSelectItemSeq,
      @JsonInclude(JsonInclude.Include.NON_NULL) String insuranceCode,
      @JsonInclude(JsonInclude.Include.NON_NULL) Boolean codeMatched,
      @JsonInclude(JsonInclude.Include.NON_NULL) Boolean similar,
      @JsonInclude(JsonInclude.Include.NON_NULL) Boolean unclear,
      @JsonInclude(JsonInclude.Include.NON_NULL) PrescriptionDose prescription) {
    public DrugGroup(int readNo, String readName, String lookup, List<Candidate> candidates) {
      this(readNo, readName, lookup, candidates, null);
    }
    public DrugGroup(int readNo, String readName, String lookup, List<Candidate> candidates, String autoSelectItemSeq) {
      this(readNo, readName, lookup, candidates, autoSelectItemSeq, null, null, null, null, null);
    }
    /**
     * 판독 때 읽은 값(보험코드·처방 칸)을 붙인다. 코드로 맞춘 하나뿐인 후보는 아직 등록되지 않았으면 미리 고르고,
     * 비슷한 이름 후보나 흐리게 읽은 이름은 미리 고르지 않는다.
     */
    public DrugGroup withReading(String code, boolean byCode, boolean similarName, boolean unclearName, PrescriptionDose dose) {
      String auto = similarName || unclearName ? null : autoSelectItemSeq;
      if (byCode && candidates.size() == 1 && !candidates.getFirst().alreadyRegistered()) auto = candidates.getFirst().itemSeq();
      return new DrugGroup(readNo, readName, lookup, candidates, auto, code, byCode ? Boolean.TRUE : null,
          similarName ? Boolean.TRUE : null, unclearName ? Boolean.TRUE : null, dose);
    }
  }
  /** V3-REG-002: 함량 칸(baseName · strengthText · strengthMatch)은 처방전 후보에만 있음. 알약 응답은 그대로임. */
  public record Candidate(
      String itemSeq, String name, String maker, String etcOtc, String imageUrl,
      Boolean cancelled, boolean alreadyRegistered, Boolean printMatched,
      String printFront, String printBack, String shape, String color,
      @JsonInclude(JsonInclude.Include.NON_NULL) String orientation,
      @JsonInclude(JsonInclude.Include.NON_NULL) Integer evidenceRank,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<PillMatchDetail> matchDetails,
      String ingredientName,
      @JsonInclude(JsonInclude.Include.NON_NULL) String baseName,
      @JsonInclude(JsonInclude.Include.NON_NULL) String strengthText,
      @JsonInclude(JsonInclude.Include.NON_NULL) StrengthMatch strengthMatch) {
    public Candidate(String itemSeq, String name, String maker, String etcOtc, String imageUrl,
        Boolean cancelled, boolean alreadyRegistered, Boolean printMatched,
        String printFront, String printBack, String shape, String color,
        String orientation, Integer evidenceRank, List<PillMatchDetail> matchDetails,
        String ingredientName) {
      this(itemSeq, name, maker, etcOtc, imageUrl, cancelled, alreadyRegistered, printMatched,
          printFront, printBack, shape, color, orientation, evidenceRank, matchDetails, ingredientName,
          null, null, null);
    }
    public Candidate(String itemSeq, String name, String maker, String etcOtc, String imageUrl,
        Boolean cancelled, boolean alreadyRegistered, Boolean printMatched,
        String printFront, String printBack, String shape, String color,
        String orientation, Integer evidenceRank, List<PillMatchDetail> matchDetails) {
      this(itemSeq, name, maker, etcOtc, imageUrl, cancelled, alreadyRegistered, printMatched,
          printFront, printBack, shape, color, orientation, evidenceRank, matchDetails, null);
    }
    public Candidate withStrength(DrugStrength strength, StrengthMatch match) {
      return new Candidate(itemSeq, name, maker, etcOtc, imageUrl, cancelled, alreadyRegistered, printMatched,
          printFront, printBack, shape, color, orientation, evidenceRank, matchDetails, ingredientName,
          strength.baseName(), strength.text(), match);
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
