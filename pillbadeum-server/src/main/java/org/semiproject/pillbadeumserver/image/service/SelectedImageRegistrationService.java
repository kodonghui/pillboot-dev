package org.semiproject.pillbadeumserver.image.service;

import java.util.*;
import org.semiproject.pillbadeumserver.common.exception.*;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.service.*;
import org.semiproject.pillbadeumserver.image.domain.PhotoDrugSelectionRequest;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 이름 검색/사진 선택의 새 items 계약이다. 커밋이 반환된 뒤 정리하므로 IO 장애가 약 저장을 롤백하지 않는다. */
@Service
public class SelectedImageRegistrationService {
  private static final Logger log = LoggerFactory.getLogger(SelectedImageRegistrationService.class);
  private final ProfileAccessChecker access;
  private final DrugSyncService drugs;
  private final ImageDrugConfirmationService confirmation;
  private final PhotoLifecycleService lifecycle;

  public SelectedImageRegistrationService(
      ProfileAccessChecker access,
      DrugSyncService drugs,
      ImageDrugConfirmationService confirmation,
      PhotoLifecycleService lifecycle) {
    this.access = access;
    this.drugs = drugs;
    this.confirmation = confirmation;
    this.lifecycle = lifecycle;
  }

  public PhotoDrugSelectionRequest.Result register(
      long profileId, PhotoDrugSelectionRequest request) {
    access.checkOwner(profileId);
    if (request == null
        || request.source() == null
        || request.source() == DrugSource.MANUAL
        || request.items() == null
        || request.items().isEmpty()
        || request.items().size() > 10) throw invalid("저장할 약을 1~10개 선택해주세요.");
    boolean search = request.source() == DrugSource.SEARCH;
    boolean clues = request.source() == DrugSource.PILL_CLUES;
    boolean pill = clues || request.source() == DrugSource.PILL_PHOTO;
    if ((!pill && request.identificationId() != null)
        || (clues && request.identificationId() == null))
      throw invalid("식별 결과의 등록 출처와 ID를 확인해 주세요.");
    if (pill && request.items().size() != 1) throw invalid("알약 후보는 한 개만 선택해 주세요.");
    var codes = new LinkedHashSet<String>();
    var origins = new HashMap<String, DrugSource>();
    var slots = new HashSet<Integer>();
    for (var item : request.items()) {
      if (item == null || item.itemSeq() == null || !item.itemSeq().strip().matches("[0-9]{1,100}"))
        throw invalid("품목기준코드를 확인해 주세요.");
      String code = item.itemSeq().strip();
      DrugSource origin = item.source() == null ? request.source() : item.source();
      if (origin != request.source()
          && !(request.source() == DrugSource.PRESCRIPTION_PHOTO && origin == DrugSource.SEARCH))
        throw invalid("약별 등록 출처를 확인해 주세요.");
      if (item.readNo() != null && (item.readNo() < 1 || item.readNo() > 10 || pill))
        throw invalid("처방전의 약 순서를 확인해 주세요.");
      if (request.source() == DrugSource.PRESCRIPTION_PHOTO && origin == DrugSource.SEARCH
          && request.photoId() != null && item.readNo() == null)
        throw invalid("보완 검색한 약의 처방전 순서를 함께 보내 주세요.");
      if (origins.containsKey(code) && origins.get(code) != origin)
        throw invalid("같은 약의 등록 출처를 하나로 선택해 주세요.");
      if (item.readNo() != null && !slots.add(item.readNo()))
        throw invalid("약마다 후보를 하나만 선택해 주세요.");
      origins.put(code, origin);
      codes.add(code);
    }
    boolean hasPhotoItem = origins.containsValue(DrugSource.PRESCRIPTION_PHOTO) || request.source() == DrugSource.PILL_PHOTO;
    if ((search || clues) && request.photoId() != null
        || hasPhotoItem && (request.photoId() == null || request.photoId() < 1)
        || request.photoId() != null && request.photoId() < 1)
      throw invalid("등록 경로와 사진을 확인해 주세요.");
    // 사진 후보는 식별 시 이미 공식 캐시에 들어간 값이다. 이름 검색 확인만 TX 밖에서 조회한다.
    for (String code : codes) if (request.sourceOf(code) == DrugSource.SEARCH) drugs.getOrSync(code);
    PhotoDrugSelectionRequest.Result result;
    try {
      result = confirmation.confirm(profileId, request, List.copyOf(codes));
    } catch (DataIntegrityViolationException concurrent) {
      result = confirmation.confirm(profileId, request, List.copyOf(codes));
    }
    if (!search && request.photoId() != null)
      try {
        lifecycle.deleteOwned(request.photoId());
      } catch (NotFoundException alreadyDeleted) {
        /* 멱등 재전송: 같은 약이 이미 저장되고 사진도 정리된 상태임. */
      } catch (RuntimeException cleanupFailure) {
        log.warn("[선택 약 저장] cleanup=deferred type={}", cleanupFailure.getClass().getSimpleName());
      }
    return result;
  }

  private static BusinessException invalid(String s) {
    return new BusinessException(ErrorCode.INVALID_INPUT, s);
  }
}
