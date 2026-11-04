package org.semiproject.pillbadeumserver.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse.ScheduleValues;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.medication.domain.MedicationRepository;
import org.semiproject.pillbadeumserver.medication.domain.MedicationTarget;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.CommandRequest;
import org.semiproject.pillbadeumserver.medication.dto.MedicationVoiceDtos.ParsedCommand;
import org.semiproject.pillbadeumserver.medication.service.MedicationVoiceInterpreter;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * V3-CHAT-007 일정 제안 만들기의 대상 약 고르기·해석 결과별 답변·해석기 실패를 가짜 저장소·해석기로 검증한다.
 * 실제 모델이 문장을 어떻게 읽는지는 이 시험의 범위가 아니다.
 */
class ScheduleProposalServiceTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private final MedicationRepository medications = mock(MedicationRepository.class);
    private final MedicationVoiceInterpreter interpreter = mock(MedicationVoiceInterpreter.class);
    private final QuestionStore questions = mock(QuestionStore.class);
    private final ChatProposalStore proposals = mock(ChatProposalStore.class);
    private final QuestionResponse.Question question = new QuestionResponse.Question(100L, 3);
    private final QuestionResponse.Answer notice = new QuestionResponse.Answer(101L, 3, "안내", null, OffsetDateTime.now());
    private final MedicationTarget pressure = target(30L, "가상혈압정5밀리그램", "07:00,19:00");
    private SimpleAsyncTaskExecutor executor;
    private ScheduleProposalService service;

    private static MedicationTarget target(long myDrugId, String name, String times) {
        return new MedicationTarget(myDrugId, 10L, "엄마", "PARENT", name, 2, "식후 30분", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), times, LocalDateTime.of(2026, 11, 1, 9, 0), true);
    }

    private static ParsedCommand parsed(String intent, Long myDrugId, List<String> times, Integer dose) {
        return new ParsedCommand(intent, myDrugId, null, null, null, times, null, dose, null);
    }

    @BeforeEach
    void setup() {
        executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        service = new ScheduleProposalService(medications, interpreter, questions, proposals, executor,
                Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, target(31L, "가상비타민정", null)));
        when(questions.saveNotice(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), anyBoolean())).thenReturn(notice);
    }

    @AfterEach
    void close() { executor.close(); }

    private QuestionResponse.Answer propose(String target) {
        return service.propose(42L, 7L, 10L, question, "혈압약 아침 8시 한 알로 바꿔 줘", target, ChatDeadline.after(Duration.ofSeconds(5)), false);
    }

    private void interpreted(ParsedCommand command) {
        when(interpreter.parse(any(), eq(TODAY), any())).thenReturn(command);
    }

    @Test
    void readValuesBecomeACardWithTheCurrentAndProposedSchedule() {
        interpreted(parsed("CREATE_SCHEDULE", null, List.of("08:00"), 1));
        when(proposals.saveScheduleAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), anyLong(), any(), any(), any(),
                anyBoolean())).thenReturn(notice);
        assertThat(propose("가상혈압정5밀리그램")).isSameAs(notice);

        var month = new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of("07:00", "19:00"), 2, "식후 30분");
        verify(proposals).saveScheduleAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.FOUND), eq(30L),
                eq(month), eq(new ScheduleValues(month.startDate(), month.endDate(), List.of("08:00"), 1, "식후 30분")), any(),
                eq(false));
        verify(questions, never()).saveNotice(any(), any(), any(), any(), anyString(), any(), anyBoolean());
    }

    @Test
    void interpreterGetsOnlyTheChosenDrugWithoutTheProfileName() {
        interpreted(parsed("CREATE_SCHEDULE", null, List.of("08:00"), 1));
        propose("가상혈압정5밀리그램");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MedicationTarget>> sent = ArgumentCaptor.forClass(List.class);
        var request = ArgumentCaptor.forClass(CommandRequest.class);
        verify(interpreter).parse(request.capture(), eq(TODAY), sent.capture());
        assertThat(sent.getValue()).singleElement().satisfies(target -> {
            assertThat(target.myDrugId()).isEqualTo(30L);
            assertThat(target.profileName()).isEmpty();
        });
        assertThat(request.getValue()).isEqualTo(new CommandRequest("혈압약 아침 8시 한 알로 바꿔 줘", 10L, 30L, TODAY));
    }

    @Test
    void missingOrSharedNamesAskWhichDrugWithoutReading() {
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, target(32L, "가상혈압정5밀리그램", null)));
        propose("가상혈압정5밀리그램");
        propose(null);
        propose("없는약");
        verify(questions, times(3)).saveNotice(eq(42L), eq(7L), eq(10L), eq(question),
                eq(ScheduleProposalService.ASK_DRUG), any(), eq(false));
        verifyNoInteractions(interpreter, proposals);
    }

    @Test
    void anAbbreviatedNameMatchingTwoOwnedProductsListsThemToChooseFrom() {
        var five = target(33L, "가상바스크정 5밀리그램", null);
        var ten = target(34L, "가상바스크정 10밀리그램(가상성분)", null);
        assertThat(ScheduleProposalService.askDrug(List.of(pressure, five, ten), "가상바스크 아침 8시 한 알로 바꿔 줘"))
                .isEqualTo("약목록에 맞는 약이 여럿이에요. 어느 약인지 함량까지 넣어 다시 말해 주세요.\n"
                        + "· 가상바스크정 5밀리그램\n· 가상바스크정 10밀리그램(가상성분)\n예) 가상바스크정 10밀리그램(가상성분) 아침 8시로 바꿔 줘");
        assertThat(ScheduleProposalService.askDrug(List.of(pressure, five), "가상바스크 아침 8시로 바꿔 줘"))
                .isEqualTo(ScheduleProposalService.ASK_DRUG);
        assertThat(ScheduleProposalService.askDrug(List.of(five, ten), "아침 8시로 바꿔 줘")).isEqualTo(ScheduleProposalService.ASK_DRUG);
    }

    @Test
    void withOnlyOneDrugAScheduleWithoutANameIsForThatDrug() {
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure));
        interpreted(parsed("CREATE_SCHEDULE", null, List.of("08:00"), 1));
        when(proposals.saveScheduleAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), anyLong(), any(), any(), any(),
                anyBoolean())).thenReturn(notice);
        assertThat(propose(null)).isSameAs(notice);
        verify(proposals).saveScheduleAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.FOUND), eq(30L),
                any(), any(), any(), eq(false));
    }

    @Test
    void interpreterResultsThatCannotBeACardAnswerWithANotice() {
        record Case(ParsedCommand parsed, String notice) {}
        var cases = List.of(
                new Case(parsed("TARGET_NOT_FOUND", null, null, null), ScheduleProposalService.ASK_DRUG),
                new Case(parsed("CREATE_SCHEDULE", 99L, List.of("08:00"), null), ScheduleProposalService.ASK_DRUG),
                new Case(parsed("UNSUPPORTED", null, null, null), ScheduleProposalService.UNREADABLE),
                new Case(parsed("CREATE_SCHEDULE", null, List.of("19:00", "07:00"), 2), ScheduleProposalService.SAME),
                new Case(parsed("CREATE_SCHEDULE", null, null, 11), "1회 개수는 1~10개로 말해 주세요." + ScheduleProposalService.REJECTED_TAIL));
        for (var each : cases) {
            interpreted(each.parsed());
            propose("가상혈압정5밀리그램");
            verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(each.notice()), any(), eq(false));
            clearInvocations(questions);
        }
        verifyNoInteractions(proposals);
    }

    @Test
    void interpreterFailureAnswersWithAFailureNoticeAndNoCard() {
        when(interpreter.parse(any(), any(), any())).thenThrow(ExternalApiException.failed("OpenAI", "일정 항목을 읽을 수 없습니다.", null));
        propose("가상혈압정5밀리그램");
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.READ_FAILED), any(), eq(false));
        verifyNoInteractions(proposals);
    }

    @Test
    void timeoutAfterTheQuestionWasSavedFailsWithTheQuestionIdForRetry() {
        when(interpreter.parse(any(), any(), any())).thenAnswer(call -> {
            Thread.sleep(200);
            return parsed("CREATE_SCHEDULE", null, List.of("08:00"), 1);
        });
        assertThatThrownBy(() -> service.propose(42L, 7L, 10L, question, "바꿔 줘", "가상혈압정5밀리그램",
                ChatDeadline.after(Duration.ofMillis(50)), false))
                .isInstanceOfSatisfying(AnswerFailedException.class,
                        failed -> assertThat(failed.getQuestionMessageId()).isEqualTo(100L));
        verify(questions, never()).saveNotice(any(), any(), any(), any(), anyString(), any(), anyBoolean());
        verifyNoInteractions(proposals);
    }
}
