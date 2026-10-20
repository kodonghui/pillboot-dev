package org.semiproject.pillbadeumserver.drug.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.drug.domain.Drug;
import org.semiproject.pillbadeumserver.drug.domain.DrugRepository;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugCreateRequest;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugDetailResponse;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.semiproject.pillbadeumserver.drug.domain.DrugRegistered;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* 설명. 확인한 약을 my_drug 에 한 트랜잭션으로 저장한다.
 *  ----------------------------------------------------------------------------------
 *  MyDrugService.registerAll 이 외부 API(drug 보장)를 트랜잭션 밖에서 끝낸 뒤 이 서비스를 부른다.
 *  같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로
 *  저장 부분만 별도 빈으로 뺐다.
 *  ----------------------------------------------------------------------------------
 *  - 소유권 확인 · drug 보장은 호출하는 쪽(MyDrugService)이 먼저 끝낸 상태여야 한다.
 *  - 하나라도 실패하면(중복 409, 검증 400) 전부 롤백된다.
 *  - 중복 확인은 저장 직전에 한 번 더 한다. (외부 API 를 기다리는 사이 같은 약이 저장된 경우)
 *    동시에 들어온 두 요청은 DB 의 (profile_id, item_seq) UNIQUE(sql/04)가 막고,
 *    MyDrugService.registerAll 이 그 오류를 409 로 바꾼다.
 *  - COMMON-005 이벤트는 커밋 성공 뒤에만 처리되며 DUR 장애가 등록을 롤백하지 않는다.
 * */
@Service
public class MyDrugRegisterService {

    private final MyDrugRepository myDrugRepository;
    private final DrugRepository drugRepository;
    private final ApplicationEventPublisher events;

    public MyDrugRegisterService(MyDrugRepository myDrugRepository, DrugRepository drugRepository, ApplicationEventPublisher events) {
        this.myDrugRepository = myDrugRepository;
        this.drugRepository = drugRepository;
        this.events = events;
    }

    /* 목차. 1. 요청 순서대로 저장하고, 저장된 상세를 같은 순서로 돌려준다. */
    @Transactional
    public List<MyDrugDetailResponse> saveAll(Long profileId, DrugSource source,
                                              List<MyDrugCreateRequest.Item> items) {

        Set<String> registered = new HashSet<>(myDrugRepository.findItemSeqsByProfileId(profileId));

        List<MyDrug> myDrugs = items.stream()
                .map(item -> {
                    String itemSeq = item.itemSeq().strip();
                    if (registered.contains(itemSeq)) {
                        throw new BusinessException(ErrorCode.DUPLICATE,
                                "이미 등록된 약입니다. (itemSeq=" + itemSeq + ")");
                    }
                    // drug 행은 MyDrugService 에서 이미 보장했다. FK 값만 필요하므로 프록시로 가져온다.
                    Drug drug = drugRepository.getReferenceById(itemSeq);
                    return MyDrug.create(profileId, drug, source,
                            item.dosePerTime(), item.timing(),
                            item.startDate(), item.endDate(), item.doseTimes());
                })
                .toList();

        var saved = myDrugRepository.saveAll(myDrugs);
        events.publishEvent(new DrugRegistered(saved.stream().map(m -> m.getDrug().getItemSeq()).distinct().toList()));
        return saved.stream().map(MyDrugDetailResponse::from).toList();
    }
}
