package org.semiproject.pillbadeumserver.image.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.semiproject.pillbadeumserver.image.domain.*;
import org.semiproject.pillbadeumserver.image.service.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** REG-002~007의 상태 있는 사진 확인 API. 기존 /api/images 보조 API와 구분한다. */
@Tag(name = "사진으로 약 등록", description = "식별·본인 후보/사진 확인·다시 찍기")
@RestController
@RequestMapping("/api")
public class PhotoIdentificationController {
  private final PhotoIdentificationWorkflow workflow;
  private final ImageKeywordRegistrationService store;
  private final PhotoLifecycleService lifecycle;
  private final FileStorage files;
  private final PillIdentificationSessions sessions;

  public PhotoIdentificationController(
      PhotoIdentificationWorkflow workflow,
      ImageKeywordRegistrationService store,
      PhotoLifecycleService lifecycle,
      FileStorage files, PillIdentificationSessions sessions) {
    this.workflow = workflow;
    this.store = store;
    this.lifecycle = lifecycle;
    this.files = files;
    this.sessions = sessions;
  }

  @Operation(
      summary = "처방전·약봉투·알약 사진 식별",
      description = "처방전/약봉투는 image 1장, 알약은 front/back(사진 1/반대 면 2) 2장. 알약 결과는 v1 상세 계약이며 30분간 복원 가능합니다.")
  @PostMapping(
      value = {
        "/profiles/{profileId}/photo-identifications",
        "/images/profiles/{profileId}/prescription-photo/identify"
      },
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<PhotoIdentificationResponse> identify(
      @PathVariable long profileId,
      MultipartHttpServletRequest multipart,
      @RequestParam(defaultValue = "PRESCRIPTION") ImagePhotoKind kind,
      @RequestParam(required = false) MultipartFile image,
      @RequestParam(required = false) MultipartFile front,
      @RequestParam(required = false) MultipartFile back,
      @RequestParam(required = false) List<MultipartFile> images,
      @RequestParam(required = false) String previousIdentificationId) {
    if (kind != ImagePhotoKind.PILL && previousIdentificationId != null)
      throw new BusinessException(ErrorCode.INVALID_INPUT, "결과 교체는 알약 식별에서만 지원합니다.");
    List<MultipartFile> input;
    if (images != null) {
      if (image != null || front != null || back != null)
        throw new BusinessException(ErrorCode.INVALID_INPUT, "사진 입력 방식을 하나만 사용해 주세요.");
      input = images;
    } else if (kind == ImagePhotoKind.PILL) {
      if (image != null || front == null || back == null)
        throw new BusinessException(ErrorCode.INVALID_INPUT, "앞면과 뒷면 사진이 모두 필요해요.");
      input = List.of(front, back);
    } else {
      if (image == null || front != null || back != null)
        throw new BusinessException(ErrorCode.INVALID_INPUT, "처방전·약봉투 사진1장을 올려 주세요.");
      input = List.of(image);
    }
    // 같은 이름의 파일을 여러 번 보내면 단일 MultipartFile 바인딩은 첫 장만 선택한다.
    int uploadedCount = multipart.getMultiFileMap().values().stream().mapToInt(List::size).sum();
    if (uploadedCount != input.size())
      throw new BusinessException(ErrorCode.INVALID_INPUT, "사진 개수를 확인해 주세요.");
    var response = workflow.identify(profileId, kind, input, previousIdentificationId);
    var result = ResponseEntity.ok();
    if (kind == ImagePhotoKind.PILL) result.header("X-Pill-Identification-Version", "1");
    return result.body(response);
  }

  @Operation(summary = "본인 사진의 저장된 후보 재조회")
  @ApiResponse(responseCode = "200", description = "사진 식별 당시의 후보·단서를 복원함. 낱알은 v1 상세 필드가 포함됨.",
      content = @Content(mediaType = "application/json", schema = @Schema(implementation = PhotoIdentificationResponse.class)))
  @GetMapping("/photos/{photoId}/candidates")
  public ResponseEntity<?> candidates(@PathVariable long photoId) {
    if (store.owned(photoId, false).kind() == ImagePhotoKind.PILL)
      return ResponseEntity.ok().header("X-Pill-Identification-Version", "1").body(sessions.readPhoto(photoId));
    return ResponseEntity.ok(store.view(photoId));
  }

  @Operation(summary = "본인 알약 식별 결과 복원", description = "사진·직접 입력 공통. 외부 API를 다시 호출하지 않으며 만료되면 410을 반환합니다.")
  @ApiResponse(responseCode = "200", description = "ACTIVE는 상세 결과, 종료 상태는 lifecycle·만료 시각·후속 ID 또는 등록 영수증만 반환함.",
      content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
          PhotoIdentificationResponse.class, PhotoIdentificationResponse.Terminal.class})))
  @GetMapping("/profiles/{profileId}/pill-identifications/{identificationId}")
  public ResponseEntity<?> result(@PathVariable long profileId, @PathVariable String identificationId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("X-Pill-Identification-Version", "1").body(sessions.read(profileId, identificationId));
  }

  @Operation(summary = "알약 식별 취소", description = "결과와 연결 사진을 정리합니다. 취소한 결과는 저장할 수 없습니다.")
  @ApiResponse(responseCode = "204", description = "취소 완료. 응답 본문 없음.")
  @DeleteMapping("/profiles/{profileId}/pill-identifications/{identificationId}")
  public ResponseEntity<Void> cancel(@PathVariable long profileId, @PathVariable String identificationId) {
    Long photoId = sessions.cancel(profileId, identificationId);
    if (photoId != null) lifecycle.deleteGroup(photoId);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "본인 확인용 원본 사진 조회")
  @GetMapping("/photos/{photoId}/image")
  public ResponseEntity<Resource> image(@PathVariable long photoId) {
    var photo = store.ownedImage(photoId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(photo.mime()))
        .body(files.loadAsResource(photo.path(), FileStorage.DIR_TEMP));
  }

  @Operation(summary = "다시 찍기·나가기", description = "알약은 앞뒤 사진을 함께 지움. IO 실패 행은 만료 정리가 재시도함.")
  @ApiResponse(responseCode = "204", description = "사진 정리 완료. 응답 본문 없음.")
  @DeleteMapping("/photos/{photoId}")
  public ResponseEntity<Void> delete(@PathVariable long photoId) {
    lifecycle.deleteOwned(photoId);
    return ResponseEntity.noContent().build();
  }
}
