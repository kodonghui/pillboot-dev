package org.semiproject.pillbadeumserver.drug;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ForbiddenException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.semiproject.pillbadeumserver.drug.domain.DrugSource;
import org.semiproject.pillbadeumserver.drug.domain.MyDrug;
import org.semiproject.pillbadeumserver.drug.domain.MyDrugRepository;
import org.semiproject.pillbadeumserver.drug.dto.MyDrugCreateRequest;
import org.semiproject.pillbadeumserver.drug.service.DrugSyncService;
import org.semiproject.pillbadeumserver.drug.service.MyDrugRegisterService;
import org.semiproject.pillbadeumserver.drug.service.MyDrugService;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/* 설명. MyDrugService 의 업무 규칙을 확인한다. (DB · 외부 API 는 가짜로 바꾼다)
 *  - 저장: 요청 안 중복 400 · 이미 등록 409 를 외부 API 호출 전에 거르는지, 동시 저장 UNIQUE(sql/04) 오류를 409 로 바꾸는지
 *  - 상세 · 삭제: 다른 프로필의 약은 404, 소유권 확인이 먼저인지
 * */
class MyDrugServiceTests {

    private static final Long PROFILE_ID = 2L;
    private static final String ITEM_SEQ = "202005623";
    private static final String OTHER_ITEM_SEQ = "202106092";

    private final MyDrugRepository myDrugRepository = mock(MyDrugRepository.class);
    private final ProfileAccessChecker profileAccessChecker = mock(ProfileAccessChecker.class);
    private final DrugSyncService drugSyncService = mock(DrugSyncService.class);
    private final MyDrugRegisterService registerService = mock(MyDrugRegisterService.class);
    private final MyDrugService myDrugService = new MyDrugService(myDrugRepository,
            profileAccessChecker, drugSyncService, registerService,
            mock(org.semiproject.pillbadeumserver.image.service.SelectedImageRegistrationService.class));

    private final MyDrugCreateRequest request = request(ITEM_SEQ);

    /* 목차. 1. 저장 */
    @Test
    void uniqueViolationFromConcurrentSameDrugBecomesConflict() {
        // 앞의 중복 확인 때는 없었고, 저장하려는 사이 다른 요청이 같은 약을 먼저 저장했다.
        when(myDrugRepository.findItemSeqsByProfileId(PROFILE_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(ITEM_SEQ));
        when(registerService.saveAll(eq(PROFILE_ID), any(), anyList()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_my_drug_profile_item'"));

        assertErrorCode(() -> myDrugService.registerAll(PROFILE_ID, request), ErrorCode.DUPLICATE);
    }

    @Test
    void otherConstraintViolationIsNotReportedAsDuplicate() {
        when(myDrugRepository.findItemSeqsByProfileId(PROFILE_ID)).thenReturn(List.of());
        when(registerService.saveAll(eq(PROFILE_ID), any(), anyList()))
                .thenThrow(new DataIntegrityViolationException("Check constraint violated"));

        assertThatThrownBy(() -> myDrugService.registerAll(PROFILE_ID, request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameDrugTwiceInOneRequestIsRejectedBeforeCallingPublicData() {
        MyDrugCreateRequest twice = new MyDrugCreateRequest(DrugSource.PRESCRIPTION_PHOTO,
                List.of(item(ITEM_SEQ), item(" " + ITEM_SEQ + " ")));

        assertErrorCode(() -> myDrugService.registerAll(PROFILE_ID, twice), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(drugSyncService, registerService);
    }

    @Test
    void alreadyRegisteredDrugIsConflictBeforeCallingPublicData() {
        when(myDrugRepository.findItemSeqsByProfileId(PROFILE_ID)).thenReturn(List.of(ITEM_SEQ));

        assertErrorCode(() -> myDrugService.registerAll(PROFILE_ID, request), ErrorCode.DUPLICATE);
        verifyNoInteractions(drugSyncService, registerService);
    }

    @Test
    void registerChecksOwnerThenEnsuresEveryDrugThenSavesOnce() {
        MyDrugCreateRequest two = new MyDrugCreateRequest(DrugSource.PRESCRIPTION_PHOTO,
                List.of(item(ITEM_SEQ), item(OTHER_ITEM_SEQ)));
        when(myDrugRepository.findItemSeqsByProfileId(PROFILE_ID)).thenReturn(List.of());
        when(registerService.saveAll(eq(PROFILE_ID), eq(DrugSource.PRESCRIPTION_PHOTO), anyList())).thenReturn(List.of());

        myDrugService.registerAll(PROFILE_ID, two);

        InOrder order = inOrder(profileAccessChecker, drugSyncService, registerService);
        order.verify(profileAccessChecker).checkOwner(PROFILE_ID);
        order.verify(drugSyncService).getOrSync(ITEM_SEQ);
        order.verify(drugSyncService).getOrSync(OTHER_ITEM_SEQ);
        order.verify(registerService).saveAll(PROFILE_ID, DrugSource.PRESCRIPTION_PHOTO, two.drugs());
    }

    @Test
    void foreignProfileStopsRegisterBeforeAnything() {
        doThrow(new ForbiddenException()).when(profileAccessChecker).checkOwner(4L);

        assertThatThrownBy(() -> myDrugService.registerAll(4L, request)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(myDrugRepository, drugSyncService, registerService);
    }

    /* 목차. 2. 상세 */
    @Test
    void detailOfOtherProfilesDrugIsNotFound() {
        when(myDrugRepository.findWithDrugById(36L)).thenReturn(Optional.of(MyDrug.createCustom(3L, "혈압약")));

        assertThatThrownBy(() -> myDrugService.getMyDrug(PROFILE_ID, 36L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void detailOfMissingDrugIsNotFound() {
        when(myDrugRepository.findWithDrugById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> myDrugService.getMyDrug(PROFILE_ID, 99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void detailOfOwnDrugIsReturned() {
        when(myDrugRepository.findWithDrugById(35L)).thenReturn(Optional.of(MyDrug.createCustom(PROFILE_ID, "혈압약")));

        assertThat(myDrugService.getMyDrug(PROFILE_ID, 35L).name()).isEqualTo("혈압약");
    }

    /* 목차. 3. 삭제 */
    @Test
    void deleteRemovesOwnDrug() {
        MyDrug mine = MyDrug.createCustom(PROFILE_ID, "혈압약");
        when(myDrugRepository.findById(35L)).thenReturn(Optional.of(mine));

        myDrugService.deleteMyDrug(PROFILE_ID, 35L);

        verify(myDrugRepository).delete(mine);
    }

    @Test
    void deleteOfOtherProfilesDrugIsNotFoundAndDeletesNothing() {
        when(myDrugRepository.findById(36L)).thenReturn(Optional.of(MyDrug.createCustom(3L, "혈압약")));

        assertThatThrownBy(() -> myDrugService.deleteMyDrug(PROFILE_ID, 36L)).isInstanceOf(NotFoundException.class);
        verify(myDrugRepository, never()).delete(any(MyDrug.class));
    }

    @Test
    void deleteOfAlreadyDeletedDrugIsNotFound() {
        when(myDrugRepository.findById(35L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> myDrugService.deleteMyDrug(PROFILE_ID, 35L)).isInstanceOf(NotFoundException.class);
        verify(myDrugRepository, never()).delete(any(MyDrug.class));
    }

    @Test
    void foreignProfileStopsDeleteBeforeReadingTheDrug() {
        doThrow(new ForbiddenException()).when(profileAccessChecker).checkOwner(4L);

        assertThatThrownBy(() -> myDrugService.deleteMyDrug(4L, 35L)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(myDrugRepository);
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(code));
    }

    private static MyDrugCreateRequest request(String itemSeq) {
        return new MyDrugCreateRequest(DrugSource.SEARCH, List.of(item(itemSeq)));
    }

    private static MyDrugCreateRequest.Item item(String itemSeq) {
        return new MyDrugCreateRequest.Item(itemSeq, 1, "식후 30분",
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 26), null);
    }
}
