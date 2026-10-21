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
import org.springframework.context.ApplicationEventPublisher;
import org.semiproject.pillbadeumserver.drug.domain.DrugRegistered;
import java.util.List;

/** 선택된 약품을 기존 내 약 목록 모델을 이용해 등록 */
@Service
public class PillDrugRegistrationService {

    private final MyDrugRepository myDrugRepository;
    private final ProfileAccessChecker profileAccessChecker;
    private final DrugSyncService drugSyncService;
    private final ApplicationEventPublisher events;

    public PillDrugRegistrationService(MyDrugRepository myDrugRepository,
                                       ProfileAccessChecker profileAccessChecker,
                                       DrugSyncService drugSyncService, ApplicationEventPublisher events) {
        this.myDrugRepository = myDrugRepository;
        this.profileAccessChecker = profileAccessChecker;
        this.drugSyncService = drugSyncService;
        this.events = events;
    }

    public MyDrugListResponse register(Long profileId, PillDrugRegistrationRequest request) {
        profileAccessChecker.checkOwner(profileId);
        if (request != null && (request.source() == DrugSource.PILL_PHOTO || request.source() == DrugSource.PILL_CLUES))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알약 식별은 결과 ID와 선택 후보로 저장해 주세요.");
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
        var saved = myDrugRepository.save(myDrug);
        // 이 메서드는 TX 밖이므로 repository의 저장 커밋이 반환된 뒤에만 발행한다.
        events.publishEvent(new DrugRegistered(List.of(itemSeq)));
        return MyDrugListResponse.from(saved);
    }
}
