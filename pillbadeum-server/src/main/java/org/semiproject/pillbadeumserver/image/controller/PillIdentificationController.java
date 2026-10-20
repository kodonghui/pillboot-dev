package org.semiproject.pillbadeumserver.image.controller;

import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillIdentificationCandidate;
import org.semiproject.pillbadeumserver.image.domain.PillDrugRegistrationRequest;
import org.semiproject.pillbadeumserver.image.domain.PillPhotoIdentificationResponse;
import org.semiproject.pillbadeumserver.image.domain.ImagePhotoKind;
import org.semiproject.pillbadeumserver.image.domain.PrescriptionPhotoIdentificationResponse;
import org.semiproject.pillbadeumserver.image.domain.TemporaryPhotoUploadResponse;
import org.semiproject.pillbadeumserver.image.service.PillDrugRegistrationService;
import org.semiproject.pillbadeumserver.image.service.PillDrugIdentificationService;
import org.semiproject.pillbadeumserver.image.service.PillPhotoIdentificationService;
import org.semiproject.pillbadeumserver.image.service.PrescriptionPhotoIdentificationService;
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
    private final PrescriptionPhotoIdentificationService prescriptionPhotoIdentificationService;
    private final TemporaryPhotoUploadService temporaryPhotoUploadService;

    public PillIdentificationController(PillDrugIdentificationService identificationService,
                                        PillDrugRegistrationService registrationService,
                                        PillPhotoIdentificationService photoIdentificationService,
                                        PrescriptionPhotoIdentificationService prescriptionPhotoIdentificationService,
                                        TemporaryPhotoUploadService temporaryPhotoUploadService) {
        this.identificationService = identificationService;
        this.registrationService = registrationService;
        this.photoIdentificationService = photoIdentificationService;
        this.prescriptionPhotoIdentificationService = prescriptionPhotoIdentificationService;
        this.temporaryPhotoUploadService = temporaryPhotoUploadService;
    }

    /** OCR/시각적 식별 값을 입력받아 순위가 매겨진 약물 후보를 반환 */
    @PostMapping("/identify")
    public List<PillIdentificationCandidate> identify(@RequestBody PillIdentification input) {
        return identificationService.identify(input);
    }

    /** 임시 알약 사진 한두 장을 읽어 들여 공개 데이터 기반의 후보 항목들을 반환 */
    @PostMapping(value = "/pill-photo/identify", consumes = "multipart/form-data")
    public PillPhotoIdentificationResponse identifyPhotos(
            @RequestParam(value = "profileId", required = false) Long profileId,
            @RequestParam("images") List<MultipartFile> images) {
        return photoIdentificationService.identify(profileId, images);
    }

    /** 처방전/약봉투 사진에서 읽은 약 이름과 조회 후보를 기존 OCR 테이블에 등록 */
    @PostMapping(value = "/profiles/{profileId}/prescription-photo/identify", consumes = "multipart/form-data")
    public PrescriptionPhotoIdentificationResponse identifyPrescriptionPhotos(
            @PathVariable Long profileId,
            @RequestParam(value = "kind", defaultValue = "PRESCRIPTION") ImagePhotoKind kind,
            @RequestParam("images") List<MultipartFile> images) {
        return prescriptionPhotoIdentificationService.identify(profileId, kind, images);
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
