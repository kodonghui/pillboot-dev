package org.semiproject.pillbadeumserver.drug.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.image.domain.PhotoDrugSelectionRequest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/* 설명. 확인한 약 저장 요청 (POST /api/profiles/{profileId}/drugs)
 *  ----------------------------------------------------------------------------------
 *  세 가지 등록 방법이 모두 이 요청 하나를 쓴다. 약은 배열로 받는다.
 *  - 이름 검색        : source = SEARCH,             drugs 1개
 *  - 알약 사진        : source = PILL_PHOTO,         drugs 1개 (이미지 팀 분석 결과 확인 후)
 *  - 처방전 · 약봉투  : source = PRESCRIPTION_PHOTO, drugs 1~10개 (이미지 팀 분석 결과 확인 후)
 *  한 요청의 약은 한 트랜잭션으로 저장한다. 하나라도 실패하면 전부 저장되지 않는다.
 *  ----------------------------------------------------------------------------------
 *  요청 예)
 *  {
 *    "source": "PRESCRIPTION_PHOTO",
 *    "drugs": [
 *      { "itemSeq": "202106092", "dosePerTime": 1, "timing": "식후 30분",
 *        "startDate": "2026-10-19", "endDate": "2026-10-25", "doseTimes": ["08:00", "13:00", "19:00"] },
 *      { "itemSeq": "200808876", "dosePerTime": 1, "timing": null,
 *        "startDate": "2026-10-19", "endDate": "2026-11-18", "doseTimes": null }
 *    ]
 *  }
 *  ----------------------------------------------------------------------------------
 *  - 여기서는 형식만 검사한다. (빠진 값, 길이, 숫자 범위)
 *    시작일 ≤ 종료일, 복용 시각 중복 같은 업무 규칙은 MyDrug 엔티티가 다시 검사한다.
 *  - doseTimes 를 보내지 않으면 기본 시각(07:00, 11:00, 17:00)으로 저장한다.
 *  - 1회 개수 1~10, 복용 시점 20자 이하는 팀 명세(복용정보 수정)와 복약 일정 수정(PUT .../schedule)과 같은 규칙이다.
 *    한쪽만 바꾸면 등록은 되는데 수정은 안 되는 값이 생기므로 함께 바꾼다.
 *  - 이미지 분석 결과 사용 시 itemSeq 는 이미지 팀이 확정해 넘겨준 품목기준코드를 그대로 쓴다.
 * */
public record MyDrugCreateRequest(

        @NotNull(message = "등록 방법(source)을 선택해주세요.")
        DrugSource source,

        @Size(max = MAX_DRUGS, message = "한 번에 최대 " + MAX_DRUGS + "개까지 저장할 수 있습니다.")
        List<@NotNull(message = "약 정보가 비어 있습니다.") @Valid Item> drugs,

        Long photoId,
        @Size(max = MAX_DRUGS, message = "한 번에 최대 " + MAX_DRUGS + "개까지 저장할 수 있습니다.")
        List<PhotoDrugSelectionRequest.Item> items,
        String identificationId
) {

    // 기존 복용정보 포함 요청도 유지한다. 후보 선택 요청(items)과 한 본문에 섞지는 않는다.
    public MyDrugCreateRequest(DrugSource source, List<Item> drugs) {
        this(source, drugs, null, null, null);
    }

    public MyDrugCreateRequest(DrugSource source, List<Item> drugs, Long photoId,
                               List<PhotoDrugSelectionRequest.Item> items) {
        this(source, drugs, photoId, items, null);
    }

    @JsonIgnore
    @AssertTrue(message = "저장할 약을 1개 이상 보내주세요.")
    public boolean isValidPayload() {
        return (drugs != null && !drugs.isEmpty() && items == null && photoId == null && identificationId == null)
                || (drugs == null && items != null && !items.isEmpty());
    }

    public static final int MAX_DRUGS = 10;   // 처방전에서 읽는 약 최대 개수 (ocr_candidate.read_no 1~10)
    public static final int MAX_DOSE_PER_TIME = 10;     // 1회 개수 최대 (팀 명세 · 일정 수정과 같음)
    public static final int MAX_TIMING_LENGTH = 20;     // 복용 시점 최대 글자 수 (팀 명세 · 일정 수정과 같음)

    /* 설명. 저장할 약 하나와 그 복용정보 */
    @Schema(name = "MyDrugCreateItem")
    public record Item(

            @NotBlank(message = "품목기준코드(itemSeq)를 입력해주세요.")
            @Pattern(regexp = "\\d{1,100}", message = "품목기준코드(itemSeq)는 숫자만 입력할 수 있습니다.")
            String itemSeq,

            @NotNull(message = "1회 복용량을 입력해주세요.")
            @Min(value = 1, message = "1회 복용량은 1개 이상이어야 합니다.")
            @Max(value = MAX_DOSE_PER_TIME, message = "1회 개수는 1부터 " + MAX_DOSE_PER_TIME + "까지 숫자로 넣어 주세요.")
            Integer dosePerTime,

            @Size(max = MAX_TIMING_LENGTH, message = "복용 시점은 " + MAX_TIMING_LENGTH + "자까지 쓸 수 있어요.")
            String timing,

            @NotNull(message = "복용 시작일을 입력해주세요.")
            LocalDate startDate,

            @NotNull(message = "복용 종료일을 입력해주세요.")
            LocalDate endDate,

            @Size(max = 3, message = "복용 시각은 하루 최대 3개까지 입력할 수 있습니다.")
            List<@NotNull(message = "복용 시각을 확인해주세요.") LocalTime> doseTimes
    ) {
    }
}
