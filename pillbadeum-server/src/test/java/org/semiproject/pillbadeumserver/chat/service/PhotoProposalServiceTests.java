package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.image.domain.ImagePhotoKind;
import org.semiproject.pillbadeumserver.image.domain.PhotoIdentificationResponse;
import org.semiproject.pillbadeumserver.image.service.PhotoIdentificationWorkflow;
import org.semiproject.pillbadeumserver.image.service.PhotoLifecycleService;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** V3-CHAT-006: 판독 결과마다 어떤 답변·카드 후보를 저장하는지와 사진 정리를 가짜 판독·저장소로 검증한다. */
class PhotoProposalServiceTests {
    private final PhotoIdentificationWorkflow workflow = mock(PhotoIdentificationWorkflow.class);
    private final PhotoLifecycleService lifecycle = mock(PhotoLifecycleService.class);
    private final ChatProposalStore store = mock(ChatProposalStore.class);
    private final PhotoProposalService service = new PhotoProposalService(workflow, lifecycle, store);
    private final MockMultipartFile image = new MockMultipartFile("image", "rx.jpg", "image/jpeg", new byte[]{1, 2, 3});
    private final ChatDeadline deadline = ChatDeadline.after(Duration.ofSeconds(5));
    private final QuestionResponse saved = QuestionResponse.answered(new QuestionResponse.Question(100L, 3),
            new QuestionResponse.Answer(101L, 3, "안내", null, null));

    private static PhotoIdentificationResponse.Candidate candidate(String code, String name, StrengthMatch strength) {
        return new PhotoIdentificationResponse.Candidate(code, name, "시험제약", "OTC", null, false, false, null, null, null,
                null, null, null, null, null, null, "가상약정", null, strength);
    }

    private static PhotoIdentificationResponse identified(Long photoId, String status, PhotoIdentificationResponse.DrugGroup... groups) {
        return new PhotoIdentificationResponse(ImagePhotoKind.PRESCRIPTION, photoId, null, status, null, null, List.of(groups), List.of());
    }

    private QuestionResponse propose() {
        return service.propose(42L, 7L, 10L, image, deadline);
    }

    @Test
    void readNamesWithCandidatesBecomeACardAndThePhotoIsDeleted() {
        var first = candidate("199900001", "가상약정10밀리그램", StrengthMatch.MATCH);
        var second = candidate("199900002", "가상약정20밀리그램", StrengthMatch.DIFFERENT);
        when(workflow.identify(10L, ImagePhotoKind.PRESCRIPTION, List.of(image))).thenReturn(identified(55L, "OK",
                new PhotoIdentificationResponse.DrugGroup(1, "가상약정10mg", "OK", List.of(first, second)),
                new PhotoIdentificationResponse.DrugGroup(2, "없는약", "NOT_FOUND", List.of())));
        when(store.savePhotoTurn(eq(42L), eq(7L), eq(10L), anyString(), anyString(), anyList(), any())).thenReturn(saved);

        assertThat(propose()).isSameAs(saved);
        verify(store).savePhotoTurn(eq(42L), eq(7L), eq(10L), eq(PhotoProposalService.TURN),
                eq(RegistrationProposalService.FOUND + "\n찾지 못한 이름: '없는약'. 포장에 적힌 제품명과 함량으로 다시 말해 주세요."),
                argThat(picked -> picked.size() == 2 && picked.get(0).drug().itemSeq().equals("199900001")
                        && picked.get(0).mention().equals("가상약정10mg") && picked.get(0).strength() == StrengthMatch.MATCH
                        && picked.get(1).strength() == StrengthMatch.DIFFERENT),
                eq(deadline));
        verify(lifecycle).deleteOwned(55L);
    }

    @Test
    void unreadablePhotoAsksForANewPhotoWithoutACard() {
        when(workflow.identify(anyLong(), any(), anyList())).thenReturn(identified(null, "UNREADABLE"));
        propose();
        verify(store).savePhotoTurn(eq(42L), eq(7L), eq(10L), eq(PhotoProposalService.TURN), eq(PhotoProposalService.RETAKE),
                eq(List.of()), eq(deadline));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void namesWithoutCandidatesAskForTheNameInText() {
        when(workflow.identify(anyLong(), any(), anyList())).thenReturn(identified(null, "NOT_FOUND",
                new PhotoIdentificationResponse.DrugGroup(1, "가상약정", "NOT_FOUND", List.of()),
                new PhotoIdentificationResponse.DrugGroup(2, "없는약", "NOT_FOUND", List.of())));
        propose();
        verify(store).savePhotoTurn(eq(42L), eq(7L), eq(10L), eq(PhotoProposalService.TURN),
                eq(PhotoProposalService.NOT_FOUND + "\n읽은 이름: '가상약정', '없는약'."), eq(List.of()), eq(deadline));
    }

    @Test
    void readingOrPublicDataFailureLeavesAFailureNotice() {
        when(workflow.identify(anyLong(), any(), anyList())).thenThrow(ExternalApiException.failed("사진 판독", "장애", null));
        propose();
        verify(store).savePhotoTurn(eq(42L), eq(7L), eq(10L), eq(PhotoProposalService.TURN), eq(PhotoProposalService.FAILED),
                eq(List.of()), eq(deadline));
    }

    @Test
    void fileProblemsSaveNothing() {
        when(workflow.identify(anyLong(), any(), anyList())).thenThrow(new BusinessException(ErrorCode.INVALID_FILE, "형식"));
        assertThatThrownBy(this::propose).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, lifecycle);
    }

    @Test
    void photoIsDeletedEvenWhenSavingFailsAndCleanupFailureDoesNotHideTheAnswer() {
        when(workflow.identify(anyLong(), any(), anyList())).thenReturn(identified(55L, "OK",
                new PhotoIdentificationResponse.DrugGroup(1, "가상약정", "OK", List.of(candidate("199900001", "가상약정", null)))));
        when(store.savePhotoTurn(any(), any(), any(), anyString(), anyString(), anyList(), any()))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_REQUEST, "진행 중")).thenReturn(saved);
        assertThatThrownBy(this::propose).isInstanceOf(BusinessException.class);
        verify(lifecycle).deleteOwned(55L);

        doThrow(new IllegalStateException("disk")).when(lifecycle).deleteOwned(55L);
        assertThat(propose()).isSameAs(saved);
        verify(store, never()).saveRegisterAnswer(any(), any(), any(), any(), anyString(), anyList(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void candidatesWithoutACachedNameAreSkippedAndAtMostFivePerName() {
        var many = new java.util.ArrayList<PhotoIdentificationResponse.Candidate>();
        many.add(candidate("199900000", null, StrengthMatch.MATCH));
        for (int i = 1; i <= 6; i++) many.add(candidate("19990000" + i, "가상약정" + i, null));
        var picked = PhotoProposalService.candidates(new PhotoIdentificationResponse.DrugGroup(1, "가상약정", "OK", many));
        assertThat(picked).hasSize(RegistrationCandidates.PER_MENTION)
                .extracting(c -> c.drug().itemSeq()).startsWith("199900001");
        assertThat(picked).allSatisfy(c -> assertThat(c.strength()).isEqualTo(StrengthMatch.UNKNOWN));
    }
}
