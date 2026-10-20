package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/* 설명. 내 약 (my_drug) 업무 처리
 *  ----------------------------------------------------------------------------------
 *  모든 공개 메서드는 맨 앞에서 ProfileAccessChecker.checkOwner(profileId) 를 호출한다.
 *  (없는 프로필 404, 남의 프로필 403, 비로그인 401)
 *  ----------------------------------------------------------------------------------
 *  지금 구현된 기능
 *  - getMyDrugs     : 내 약 목록 (GET /api/profiles/{profileId}/drugs)
 *  이후 추가할 기능 (계획 문서 5장 순서)
 *  - 저장(POST) · 상세(GET) · 수정(PATCH) · 삭제(DELETE)
 *  ----------------------------------------------------------------------------------
 *  - 엔티티는 트랜잭션 안에서 DTO 로 바꿔서 돌려준다. (open-in-view: false)
 * */
@Service
@Transactional(readOnly = true)
public class MyDrugService {

    private final MyDrugRepository myDrugRepository;
    private final ProfileAccessChecker profileAccessChecker;

    public MyDrugService(MyDrugRepository myDrugRepository,
                         ProfileAccessChecker profileAccessChecker) {
        this.myDrugRepository = myDrugRepository;
        this.profileAccessChecker = profileAccessChecker;
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
}
