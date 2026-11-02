package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.drug.domain.StrengthMatch;
import org.semiproject.pillbadeumserver.image.domain.PhotoCandidateData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** V3-CHAT-002의 카드 고르기·답변 글·후보 없음·공공데이터 장애를 가짜 저장소로 검증한다. */
class RegistrationProposalServiceTests {
    private final RegistrationCandidates candidates = mock(RegistrationCandidates.class);
    private final QuestionStore questions = mock(QuestionStore.class);
    private final ChatProposalStore proposals = mock(ChatProposalStore.class);
    private final RegistrationProposalService service = new RegistrationProposalService(candidates, questions, proposals);
    private final QuestionResponse.Question question = new QuestionResponse.Question(100L, 3);
    private final QuestionResponse.Answer notice = new QuestionResponse.Answer(101L, 3, "안내", null, OffsetDateTime.now());

    private static RegistrationCandidates.Candidate candidate(String mention, String code) {
        var drug = new PhotoCandidateData(code, "가상약정", "시험제약", "OTC", null, null, null, null, null, null, null,
                null, null, "미확인", null, false);
        return new RegistrationCandidates.Candidate(mention, drug, StrengthMatch.UNKNOWN);
    }

    private QuestionResponse.Answer propose(List<String> mentions, ChatDeadline deadline) {
        return service.propose(42L, 7L, 10L, question, mentions, deadline, false);
    }

    @Test
    void cardTakesOneCandidatePerNameInTurnAndStopsAtTen() {
        var found = new ArrayList<List<RegistrationCandidates.Candidate>>();
        for (int name = 0; name < 10; name++) {
            var list = new ArrayList<RegistrationCandidates.Candidate>();
            for (int i = 0; i < 5; i++) list.add(candidate("이름" + name, "1999" + name + i));
            found.add(list);
        }
        var picked = RegistrationProposalService.pick(found);
        assertThat(picked).hasSize(RegistrationProposalService.MAX_ITEMS)
                .extracting(c -> c.drug().itemSeq()).containsExactly("199900", "199910", "199920", "199930", "199940",
                        "199950", "199960", "199970", "199980", "199990");

        var few = RegistrationProposalService.pick(List.of(
                List.of(candidate("가", "1"), candidate("가", "2"), candidate("가", "3")),
                List.of(candidate("나", "2"), candidate("나", "4")), List.of()));
        assertThat(few).extracting(c -> c.drug().itemSeq()).containsExactly("1", "2", "4", "3");
    }

    @Test
    void foundCandidatesBecomeACardAndMissingNamesAreAskedAgain() {
        var found = List.of(List.of(candidate("타이레놀 500", "199900002")), List.<RegistrationCandidates.Candidate>of());
        when(candidates.find(eq(List.of("타이레놀 500", "없는약")), any())).thenReturn(found);
        when(proposals.saveRegisterAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), anyList(), any(), eq(false)))
                .thenReturn(notice);
        assertThat(propose(List.of("타이레놀 500", "없는약"), ChatDeadline.after(Duration.ofSeconds(5)))).isSameAs(notice);
        verify(proposals).saveRegisterAnswer(eq(42L), eq(7L), eq(10L), eq(question),
                eq(RegistrationProposalService.FOUND + "\n찾지 못한 이름: '없는약'. 포장에 적힌 제품명과 함량으로 다시 말해 주세요."),
                eq(List.of(found.getFirst().getFirst())), any(), eq(false));
        assertThat(RegistrationProposalService.describe(List.of("가"), List.of(List.of(candidate("가", "1")))))
                .isEqualTo(RegistrationProposalService.FOUND);
    }

    @Test
    void noCandidateAnswersWithoutACard() {
        when(candidates.find(anyList(), any())).thenReturn(List.of(List.of()));
        when(questions.saveNotice(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), anyBoolean())).thenReturn(notice);
        propose(List.of("없는약"), ChatDeadline.after(Duration.ofSeconds(5)));
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(RegistrationProposalService.NOT_FOUND), any(), eq(false));
        verifyNoInteractions(proposals);
    }

    @Test
    void registrationWithoutANameAsksForOneWithoutSearching() {
        when(questions.saveNotice(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), anyBoolean())).thenReturn(notice);
        propose(List.of(), ChatDeadline.after(Duration.ofSeconds(5)));
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(RegistrationProposalService.ASK_NAME), any(), eq(false));
        verifyNoInteractions(candidates, proposals);
    }

    @Test
    void publicDataFailureAnswersWithAFailureNoticeAndNoCard() {
        when(candidates.find(anyList(), any())).thenThrow(ExternalApiException.failed("공공데이터포털", "장애", null));
        when(questions.saveNotice(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), anyBoolean())).thenReturn(notice);
        propose(List.of("타이레놀"), ChatDeadline.after(Duration.ofSeconds(5)));
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(RegistrationProposalService.SEARCH_FAILED), any(), eq(false));
        verifyNoInteractions(proposals);
    }

    @Test
    void timeoutAfterTheQuestionWasSavedFailsWithTheQuestionIdForRetry() throws Exception {
        var deadline = ChatDeadline.after(Duration.ofMillis(50));
        when(candidates.find(anyList(), any())).thenAnswer(call -> {
            Thread.sleep(100);
            throw ExternalApiException.timeout("챗봇", null);
        });
        assertThatThrownBy(() -> propose(List.of("타이레놀"), deadline)).isInstanceOfSatisfying(AnswerFailedException.class,
                failed -> assertThat(failed.getQuestionMessageId()).isEqualTo(100L));
        verify(questions, never()).saveNotice(any(), any(), any(), any(), anyString(), any(), anyBoolean());
    }
}
