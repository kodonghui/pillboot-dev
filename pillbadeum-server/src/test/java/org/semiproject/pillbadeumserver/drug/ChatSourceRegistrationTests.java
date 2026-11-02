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
import org.semiproject.pillbadeumserver.image.domain.PhotoDrugSelectionRequest;
import org.semiproject.pillbadeumserver.image.domain.PillDrugRegistrationRequest;
import org.semiproject.pillbadeumserver.image.service.ImageDrugConfirmationService;
import org.semiproject.pillbadeumserver.image.service.PhotoLifecycleService;
import org.semiproject.pillbadeumserver.image.service.PillCatalog;
import org.semiproject.pillbadeumserver.image.service.PillDrugRegistrationService;
import org.semiproject.pillbadeumserver.image.service.SelectedImageRegistrationService;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/* 설명. V3-CHAT-003: 등록 방법 '대화'(CHAT)는 등록 제안 확정으로만 저장한다.
 *  기존 약 저장 경로 세 곳(drugs 방식 · items 방식 · 옛 알약 경로)이 CHAT 을 400 으로 거르고,
 *  공공데이터 조회와 저장 전에 멈추는지 확인한다. (DB · 외부 API 는 가짜로 바꾼다)
 * */
class ChatSourceRegistrationTests {

    private static final Long PROFILE_ID = 2L;
    private static final String ITEM_SEQ = "202005623";

    private final MyDrugRepository myDrugRepository = mock(MyDrugRepository.class);
    private final ProfileAccessChecker profileAccessChecker = mock(ProfileAccessChecker.class);
    private final DrugSyncService drugSyncService = mock(DrugSyncService.class);
    private final MyDrugRegisterService registerService = mock(MyDrugRegisterService.class);
    private final ImageDrugConfirmationService confirmation = mock(ImageDrugConfirmationService.class);

    @Test
    void drugsRequestWithChatSourceIsRejectedBeforePublicData() {
        var service = new MyDrugService(myDrugRepository, profileAccessChecker, drugSyncService, registerService,
                mock(SelectedImageRegistrationService.class), mock(PillCatalog.class));
        var request = new MyDrugCreateRequest(DrugSource.CHAT, List.of(new MyDrugCreateRequest.Item(ITEM_SEQ, 1, null,
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8), null)));

        assertInvalidInput(() -> service.registerAll(PROFILE_ID, request));
        verifyNoInteractions(myDrugRepository, drugSyncService, registerService);
    }

    @Test
    void selectedItemsWithChatSourceAreRejectedBeforePublicData() {
        var service = new SelectedImageRegistrationService(profileAccessChecker, drugSyncService, confirmation,
                mock(PhotoLifecycleService.class));
        var request = new PhotoDrugSelectionRequest(DrugSource.CHAT, null,
                List.of(new PhotoDrugSelectionRequest.Item(ITEM_SEQ, null, null)));

        assertInvalidInput(() -> service.register(PROFILE_ID, request));
        verifyNoInteractions(drugSyncService, confirmation);
    }

    @Test
    void legacyPillPathWithChatSourceIsRejectedBeforePublicData() {
        var service = new PillDrugRegistrationService(myDrugRepository, profileAccessChecker, drugSyncService,
                mock(ApplicationEventPublisher.class));
        var day = LocalDate.of(2026, 11, 2);
        var request = new PillDrugRegistrationRequest(ITEM_SEQ, DrugSource.CHAT, 1, null, day, day,
                List.of(LocalTime.of(7, 0)));

        assertInvalidInput(() -> service.register(PROFILE_ID, request));
        verifyNoInteractions(myDrugRepository, drugSyncService);
    }

    private static void assertInvalidInput(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT))
                .hasMessageContaining("등록 제안 카드");
    }
}
