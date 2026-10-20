package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugCreateRequest;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.drug.service.MyDrugRegisterService;
import org.semiproject.pillbadeumserver.drug.service.MyDrugService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/* 설명. 같은 약을 거의 동시에 저장했을 때 DB UNIQUE(sql/04) 오류를 409 로 바꾸는지 확인한다. */
class MyDrugServiceTests {

    private static final Long PROFILE_ID = 2L;
    private static final String ITEM_SEQ = "202005623";

    private final MyDrugRepository myDrugRepository = mock(MyDrugRepository.class);
    private final MyDrugRegisterService registerService = mock(MyDrugRegisterService.class);
    private final MyDrugService myDrugService = new MyDrugService(myDrugRepository,
            mock(ProfileAccessChecker.class), mock(DrugSyncService.class), registerService);

    private final MyDrugCreateRequest request = new MyDrugCreateRequest(DrugSource.SEARCH, List.of(
            new MyDrugCreateRequest.Item(ITEM_SEQ, 1, "식후 30분",
                    LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 26), null)));

    @Test
    void uniqueViolationFromConcurrentSameDrugBecomesConflict() {
        // 앞의 중복 확인 때는 없었고, 저장하려는 사이 다른 요청이 같은 약을 먼저 저장했다.
        when(myDrugRepository.findItemSeqsByProfileId(PROFILE_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(ITEM_SEQ));
        when(registerService.saveAll(eq(PROFILE_ID), any(), anyList()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_my_drug_profile_item'"));

        assertThatThrownBy(() -> myDrugService.registerAll(PROFILE_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.DUPLICATE));
    }

    @Test
    void otherConstraintViolationIsNotReportedAsDuplicate() {
        when(myDrugRepository.findItemSeqsByProfileId(PROFILE_ID)).thenReturn(List.of());
        when(registerService.saveAll(eq(PROFILE_ID), any(), anyList()))
                .thenThrow(new DataIntegrityViolationException("Check constraint violated"));

        assertThatThrownBy(() -> myDrugService.registerAll(PROFILE_ID, request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
