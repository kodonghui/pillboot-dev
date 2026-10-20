package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugCreateRequest;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugDetailResponse;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* 설명. 내 약 (my_drug) 업무 처리
 *  ----------------------------------------------------------------------------------
 *  모든 공개 메서드는 맨 앞에서 ProfileAccessChecker.checkOwner(profileId) 를 호출한다.
 *  (없는 프로필 404, 남의 프로필 403, 비로그인 401)
 *  ----------------------------------------------------------------------------------
 *  지금 구현된 기능
 *  - getMyDrugs     : 내 약 목록 (GET /api/profiles/{profileId}/drugs)
 *  - registerAll    : 확인한 약 저장 (POST /api/profiles/{profileId}/drugs)
 *  - getMyDrug      : 내 약 상세 (GET /api/profiles/{profileId}/drugs/{myDrugId})
 *  이후 추가할 기능 (계획 문서 5장 순서)
 *  - 수정(PATCH) · 삭제(DELETE)
 *  ----------------------------------------------------------------------------------
 *  - 엔티티는 트랜잭션 안에서 DTO 로 바꿔서 돌려준다. (open-in-view: false)
 * */
@Service
@Transactional(readOnly = true)
public class MyDrugService {

    private final MyDrugRepository myDrugRepository;
    private final ProfileAccessChecker profileAccessChecker;
    private final DrugSyncService drugSyncService;
    private final MyDrugRegisterService myDrugRegisterService;

    public MyDrugService(MyDrugRepository myDrugRepository,
                         ProfileAccessChecker profileAccessChecker,
                         DrugSyncService drugSyncService,
                         MyDrugRegisterService myDrugRegisterService) {
        this.myDrugRepository = myDrugRepository;
        this.profileAccessChecker = profileAccessChecker;
        this.drugSyncService = drugSyncService;
        this.myDrugRegisterService = myDrugRegisterService;
    }

    /* 목차. 1. 내 약 목록 (최근 등록순)
     *  등록된 약이 없으면 빈 배열을 돌려준다. (404 아님)
     * */
    public List<MyDrugListResponse> getMyDrugs(Long profileId) {

        profileAccessChecker.checkOwner(profileId);

        return myDrugRepository.findAllWithDrugByProfileId(profileId).stream()
                .map(MyDrugListResponse::from)
                .toList();
    }

    /* 목차. 2. 확인한 약 저장 (이름 검색 · 알약 사진 · 처방전 사진 공통)
     *  ----------------------------------------------------------------------------------
     *  1) 프로필 소유권 확인
     *  2) 요청 안에 같은 약이 두 번 있으면 400, 이미 등록된 약이면 409 (외부 API 호출 전에 빨리 거른다)
     *  3) DrugSyncService.getOrSync 로 drug 행 보장 (없으면 낱알식별 · 허가정보 조회 후 저장)
     *     - 낱알식별에 없는 약 404, 공공데이터 실패 503 → 이때는 아무것도 저장되지 않는다
     *  4) MyDrugRegisterService.saveAll 로 my_drug 를 한 트랜잭션에 저장
     *  ----------------------------------------------------------------------------------
     *  - 3) 은 외부 API 를 기다리므로 트랜잭션 밖에서 한다. (NOT_SUPPORTED: 클래스의 readOnly 트랜잭션을 쓰지 않음)
     *  - 3) 에서 앞쪽 약의 drug 행이 저장된 뒤 뒤쪽 약이 실패해도, drug 는 공용 캐시라 남겨 둔다.
     * */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MyDrugDetailResponse> registerAll(Long profileId, MyDrugCreateRequest request) {

        profileAccessChecker.checkOwner(profileId);

        List<MyDrugCreateRequest.Item> items = request.drugs();
        checkNoDuplicates(profileId, items);

        for (MyDrugCreateRequest.Item item : items) {
            drugSyncService.getOrSync(item.itemSeq());
        }

        return myDrugRegisterService.saveAll(profileId, request.source(), items);
    }

    /* 설명. 요청 안의 중복(400)과 이미 등록된 약(409)을 확인한다. */
    private void checkNoDuplicates(Long profileId, List<MyDrugCreateRequest.Item> items) {

        Set<String> inRequest = new HashSet<>();
        for (MyDrugCreateRequest.Item item : items) {
            if (!inRequest.add(item.itemSeq().strip())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "같은 약을 한 번에 두 번 저장할 수 없습니다. (itemSeq=" + item.itemSeq().strip() + ")");
            }
        }

        Set<String> registered = new HashSet<>(myDrugRepository.findItemSeqsByProfileId(profileId));
        for (String itemSeq : inRequest) {
            if (registered.contains(itemSeq)) {
                throw new BusinessException(ErrorCode.DUPLICATE,
                        "이미 등록된 약입니다. (itemSeq=" + itemSeq + ")");
            }
        }
    }

    /* 목차. 3. 내 약 상세 (복용정보 + 약 정보)
     *  ----------------------------------------------------------------------------------
     *  1) 프로필 소유권 확인 (없는 프로필 404, 남의 프로필 403)
     *  2) myDrugId 로 내 약 + drug 를 한 번에 조회 (fetch join)
     *  3) 그 약이 경로의 프로필 것이 아니면 404 (남의 약 번호를 넣어도 존재 여부를 알려주지 않는다)
     *  ----------------------------------------------------------------------------------
     *  - 외부 API 는 부르지 않는다. 효능 · 주의사항 같은 근거 출처는 guide API 에서 따로 내려준다.
     * */
    public MyDrugDetailResponse getMyDrug(Long profileId, Long myDrugId) {

        profileAccessChecker.checkOwner(profileId);

        return myDrugRepository.findWithDrugById(myDrugId)
                .filter(myDrug -> myDrug.belongsTo(profileId))
                .map(MyDrugDetailResponse::from)
                .orElseThrow(() -> new NotFoundException("등록된 약을 찾을 수 없습니다."));
    }
}
