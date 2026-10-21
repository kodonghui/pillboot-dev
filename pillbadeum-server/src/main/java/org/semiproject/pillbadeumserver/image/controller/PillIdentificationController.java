package org.semiproject.pillbadeumserver.image.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.semiproject.pillbadeumserver.image.service.PillIdentificationSessions;
import org.semiproject.pillbadeumserver.image.service.PhotoCandidateLookup;
import org.semiproject.pillbadeumserver.image.service.PhotoIdentificationWorkflow;
import org.semiproject.pillbadeumserver.image.domain.ImagePhotoKind;
import org.semiproject.pillbadeumserver.image.domain.PhotoIdentificationResponse;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final PillIdentificationSessions sessions;
    private final PhotoCandidateLookup lookup;
    private final PhotoIdentificationWorkflow workflow;

    public PillIdentificationController(PillDrugIdentificationService identificationService,
                                        PillDrugRegistrationService registrationService,
                                        PillPhotoIdentificationService photoIdentificationService,
                                        TemporaryPhotoUploadService temporaryPhotoUploadService,
                                        PillIdentificationSessions sessions, PhotoCandidateLookup lookup,
                                        PhotoIdentificationWorkflow workflow) {
        this.identificationService = identificationService;
        this.registrationService = registrationService;
        this.photoIdentificationService = photoIdentificationService;
        this.temporaryPhotoUploadService = temporaryPhotoUploadService;
        this.sessions = sessions;
        this.lookup = lookup;
        this.workflow = workflow;
    }

    /** OCR/시각적 식별 값을 입력받아 순위가 매겨진 약물 후보를 반환 */
    @Operation(summary = "알약 식별 단서로 후보 검색", description = "읽힌 각인 한 면·모양·색상 필수. 기본 응답은 후보 배열. view=details와 profileId를 보내면 30분 복원 가능한 v1 상세 결과를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "details는 v1 상세 결과, 기본/legacy는 후보 배열임.",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
                    PhotoIdentificationResponse.class, PillIdentificationCandidate[].class})))
    @PostMapping("/identify")
    public ResponseEntity<?> identify(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @io.swagger.v3.oas.annotations.media.Content(
                schema = @Schema(implementation = PillIdentification.class)))
            @RequestBody tools.jackson.databind.JsonNode input,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String previousIdentificationId) {
        var clues = PillIdentification.fromVision(input);
        if (!details(view)) {
            if (previousIdentificationId != null) throw invalid("결과 교체는 상세 조회 모드에서 요청해 주세요.");
            return ResponseEntity.ok(identificationService.identify(clues));
        }
        if (profileId == null) throw invalid("프로필 ID가 필요합니다.");
        sessions.preflight(profileId, previousIdentificationId);
        var result = lookup.pillResult(clues);
        return ResponseEntity.ok().header("X-Pill-Identification-Version", "1")
                .body(sessions.create(profileId, "CLUES", clues, result, null, List.of(), previousIdentificationId));
    }

    /** 임시 알약 사진 1~2장을 읽어 들여 공개 데이터 기반의 후보 항목들을 반환 */
    @Operation(summary = "알약 사진으로 후보 검색", description = "extracted는 사진에서 읽은 단서이고, 대조한 공식 제품명은 candidates[].itemName에 반환됩니다. front/back은 순서가 바뀌어도 비교합니다.")
    @ApiResponse(responseCode = "200", description = "details는 v1 상세 결과, 기본/legacy는 extracted/candidates 외피임.",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
                    PhotoIdentificationResponse.class, PillPhotoIdentificationResponse.class})))
    @PostMapping(value = "/pill-photo/identify", consumes = "multipart/form-data")
    public ResponseEntity<?> identifyPhotos(
            @Parameter(description = "알약 사진 1 (공식 앞면일 필요 없음)")
            @RequestParam(value = "front", required = false) MultipartFile front,
            @Parameter(description = "알약 반대 면 사진 2")
            @RequestParam(value = "back", required = false) MultipartFile back,
            @Parameter(description = "호환용 사진 배열 (1~2장, 첫 번째 앞면·두 번째 뒷면)")
            @ArraySchema(schema = @Schema(type = "string", format = "binary"), minItems = 1, maxItems = 2)
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String previousIdentificationId) {
        boolean detailed = details(view);
        if (!detailed && previousIdentificationId != null) throw invalid("결과 교체는 상세 조회 모드에서 요청해 주세요.");
        if ((front != null || back != null) && images != null && !images.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "front/back 또는 images 중 한 가지 형식으로 사진을 보내 주세요.");
        }
        if (front != null || back != null) {
            List<MultipartFile> sides = new java.util.ArrayList<>();
            if (front != null && !front.isEmpty()) sides.add(front);
            if (back != null && !back.isEmpty()) sides.add(back);
            images = sides;
        }
        if (detailed) {
            if (profileId == null) throw invalid("프로필 ID가 필요합니다.");
            return ResponseEntity.ok().header("X-Pill-Identification-Version", "1")
                    .body(workflow.identify(profileId, ImagePhotoKind.PILL, images, previousIdentificationId));
        }
        return ResponseEntity.ok(front != null || back != null
                ? photoIdentificationService.identify(images, front == null || front.isEmpty())
                : photoIdentificationService.identify(images));
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

    private static boolean details(String view) {
        if (view == null || "legacy".equals(view)) return false;
        if (!"details".equals(view)) throw invalid("view는 details만 지원합니다.");
        return true;
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}
