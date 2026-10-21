package org.semiproject.pillbadeumserver.image.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillIdentificationCandidate;
import org.semiproject.pillbadeumserver.image.domain.PillDrugRegistrationRequest;
import org.semiproject.pillbadeumserver.image.domain.PillPhotoIdentificationResponse;
import org.semiproject.pillbadeumserver.image.domain.TemporaryPhotoUploadResponse;
import org.semiproject.pillbadeumserver.image.service.PillDrugRegistrationService;
import org.semiproject.pillbadeumserver.image.service.PillDrugIdentificationService;
import org.semiproject.pillbadeumserver.image.service.PillPhotoIdentificationService;
import org.semiproject.pillbadeumserver.image.service.TemporaryPhotoUploadService;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/images")
public class PillIdentificationController {

    private final PillDrugIdentificationService identificationService;
    private final PillDrugRegistrationService registrationService;
    private final PillPhotoIdentificationService photoIdentificationService;
    private final TemporaryPhotoUploadService temporaryPhotoUploadService;

    public PillIdentificationController(PillDrugIdentificationService identificationService,
                                        PillDrugRegistrationService registrationService,
                                        PillPhotoIdentificationService photoIdentificationService,
                                        TemporaryPhotoUploadService temporaryPhotoUploadService) {
        this.identificationService = identificationService;
        this.registrationService = registrationService;
        this.photoIdentificationService = photoIdentificationService;
        this.temporaryPhotoUploadService = temporaryPhotoUploadService;
    }

    /** OCR/시각적 식별 값을 입력받아 순위가 매겨진 약물 후보를 반환 */
    @Operation(summary = "알약 식별 단서로 후보 검색", description = "제품명·업체명 없이 각인·모양·색상만으로 검색 가능. 앞뒤 각인을 함께 대조하여 관련 후보 최대 5개를 반환합니다.")
    @PostMapping("/identify")
    public List<PillIdentificationCandidate> identify(@RequestBody PillIdentification input) {
        return identificationService.identify(input);
    }

    /** 임시 알약 사진 1~2장을 읽어 들여 공개 데이터 기반의 후보 항목들을 반환 */
    @Operation(summary = "알약 사진으로 후보 검색", description = "extracted는 사진에서 읽은 단서이고, 대조한 공식 제품명은 candidates[].itemName에 반환됩니다. front/back은 순서가 바뀌어도 비교합니다.")
    @PostMapping(value = "/pill-photo/identify", consumes = "multipart/form-data")
    public PillPhotoIdentificationResponse identifyPhotos(
            @Parameter(description = "알약 앞면 사진")
            @RequestParam(value = "front", required = false) MultipartFile front,
            @Parameter(description = "알약 뒷면 사진")
            @RequestParam(value = "back", required = false) MultipartFile back,
            @Parameter(description = "호환용 사진 배열 (1~2장, 첫 번째 앞면·두 번째 뒷면)")
            @ArraySchema(schema = @Schema(type = "string", format = "binary"), minItems = 1, maxItems = 2)
            @RequestParam(value = "images", required = false) List<MultipartFile> images) {
        if ((front != null || back != null) && images != null && !images.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "front/back 또는 images 중 한 가지 형식으로 사진을 보내 주세요.");
        }
        if (front != null || back != null) {
            List<MultipartFile> sides = new java.util.ArrayList<>();
            if (front != null && !front.isEmpty()) sides.add(front);
            if (back != null && !back.isEmpty()) sides.add(back);
            return photoIdentificationService.identify(sides, front == null || front.isEmpty());
        }
        return photoIdentificationService.identify(images);
    }

    /** 임시 처방전/봉투 사진을 읽어 약물명 후보를 반환 */
    @PostMapping(value = "/profiles/{profileId}/photo-identifications", consumes = "multipart/form-data")
    public TemporaryPhotoUploadResponse uploadPrescriptionPhotos(
            @PathVariable Long profileId,
            @RequestParam("images") List<MultipartFile> images) {
        return temporaryPhotoUploadService.upload(profileId, images);
    }

    /** 선택한 약물 검색 결과를 프로필의 복용 약물 목록에 등록 */
    @PostMapping("/profiles/{profileId}/drugs")
    @ResponseStatus(HttpStatus.CREATED)
    public MyDrugListResponse register(@PathVariable Long profileId,
                                       @RequestBody PillDrugRegistrationRequest request) {
        return registrationService.register(profileId, request);
    }
}
