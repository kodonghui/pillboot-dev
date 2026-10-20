package org.semiproject.pillbadeumserver.image.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugListResponse;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.image.domain.PillDrugRegistrationRequest;
import org.springframework.stereotype.Service;

/** 선택된 약품을 기존 내 약 목록 모델을 이용해 등록 */
@Service
public class PillDrugRegistrationService {

    private final MyDrugRepository myDrugRepository;
    private final ProfileAccessChecker profileAccessChecker;
    private final DrugSyncService drugSyncService;

    public PillDrugRegistrationService(MyDrugRepository myDrugRepository,
                                       ProfileAccessChecker profileAccessChecker,
                                       DrugSyncService drugSyncService) {
        this.myDrugRepository = myDrugRepository;
        this.profileAccessChecker = profileAccessChecker;
        this.drugSyncService = drugSyncService;
    }

    public MyDrugListResponse register(Long profileId, PillDrugRegistrationRequest request) {
        profileAccessChecker.checkOwner(profileId);
        if (request == null || request.itemSeq() == null || request.itemSeq().isBlank()
                || request.dosePerTime() == null || request.startDate() == null || request.endDate() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "약 코드, 1회 복용량, 복용 시작일과 종료일을 입력해 주세요.");
        }

        String itemSeq = request.itemSeq().strip();
        if (myDrugRepository.existsByProfileIdAndDrug_ItemSeq(profileId, itemSeq)) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 이 프로필에 등록된 약입니다.");
        }

        var drug = drugSyncService.getOrSync(itemSeq);
        DrugSource source = request.source() == null ? DrugSource.SEARCH : request.source();
        MyDrug myDrug = MyDrug.create(profileId, drug, source,
                request.dosePerTime(), request.timing(), request.startDate(), request.endDate(),
                request.doseTimes());
        return MyDrugListResponse.from(myDrugRepository.save(myDrug));
    }
}
