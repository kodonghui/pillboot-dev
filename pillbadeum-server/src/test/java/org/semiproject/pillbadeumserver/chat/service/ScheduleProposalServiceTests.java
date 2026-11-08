package org.semiproject.pillbadeumserver.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    private final ScheduleSetInterpreter setInterpreter = mock(ScheduleSetInterpreter.class);
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
        service = new ScheduleProposalService(medications, interpreter, setInterpreter, questions, proposals, executor,
                Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, target(31L, "가상비타민정", null)));
        when(questions.saveNotice(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), anyBoolean())).thenReturn(notice);
    }

    @AfterEach
    void close() { executor.close(); }

    private QuestionResponse.Answer propose(String target) {
        return propose(target, "혈압약 아침 8시 한 알로 바꿔 줘");
    }

    private QuestionResponse.Answer propose(String target, String text) {
        return service.propose(42L, 7L, 10L, question, text, target, ChatDeadline.after(Duration.ofSeconds(5)), false);
    }

    private void interpreted(ParsedCommand command) {
        when(interpreter.parse(any(), eq(TODAY), any())).thenReturn(command);
    }

    @Test
    void readValuesBecomeACardWithTheCurrentAndProposedSchedule() {
        interpreted(parsed("CREATE_SCHEDULE", null, List.of("08:00"), 1));
        when(proposals.saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), any(),
                anyBoolean())).thenReturn(notice);
        assertThat(propose("가상혈압정5밀리그램")).isSameAs(notice);

        var month = new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of("07:00", "19:00"), 2, "식후 30분");
        // '아침 8시'처럼 한 끼만 말하면 그 끼의 시각만 바뀐다(2026-11-08 QA 시나리오 01, 앞서는 하루 1번 08:00으로 바뀌었음).
        verify(proposals).saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.FOUND),
                eq(List.of(new ChatProposalStore.ScheduleRow(30L, month,
                        new ScheduleValues(month.startDate(), month.endDate(), List.of("08:00", "19:00"), 1, "식후 30분"),
                        null))), any(), eq(false));
        verify(questions, never()).saveNotice(any(), any(), any(), any(), anyString(), any(), anyBoolean());
    }

    @Test
    void aTimeWithoutAMealReplacesTheTimesAndTellsTheCountChange() {
        interpreted(parsed("CREATE_SCHEDULE", null, List.of("08:00"), 1));
        when(proposals.saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), any(),
                anyBoolean())).thenReturn(notice);
        propose("가상혈압정5밀리그램", "혈압약 하루 한 번 8시에 한 알로 바꿔 줘");
        var month = new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of("07:00", "19:00"), 2, "식후 30분");
        // 하루 2번이던 약을 1번으로 바꾸면 확정 전에 볼 점으로 알린다(2026-11-08).
        verify(proposals).saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.FOUND),
                eq(List.of(new ChatProposalStore.ScheduleRow(30L, month,
                        new ScheduleValues(month.startDate(), month.endDate(), List.of("08:00"), 1, "식후 30분"),
                        "하루 2번에서 1번으로 바뀌어요."))), any(), eq(false));
    }

    // QA 2026-11-08: 한 약 일정에서 '20미리라고'가 1회 20개로 읽혀 '1회 개수는 1~10개'로 거절됐음.
    @Test
    void aSyrupAmountIsOneDoseAndTheAmountGoesToTheTiming() {
        var syrup = target(35L, "가상기침시럽", "07:00,19:00");
        when(medications.drugs(7L, 10L)).thenReturn(List.of(syrup));
        interpreted(parsed("CREATE_SCHEDULE", null, List.of("08:00", "20:00"), 20));
        when(proposals.saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), any(),
                anyBoolean())).thenReturn(notice);
        assertThat(propose("가상기침시럽", "기침시럽 아침 8시 저녁 8시에 20미리라고")).isSameAs(notice);
        var month = new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of("07:00", "19:00"), 2, "식후 30분");
        verify(proposals).saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.FOUND),
                eq(List.of(new ChatProposalStore.ScheduleRow(35L, month,
                        new ScheduleValues(month.startDate(), month.endDate(), List.of("08:00", "20:00"), 1, "식후 30분 · 1회 20mL"),
                        "1회 20mL는 복용 때 칸에 적었어요."))), any(), eq(false));
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
        when(proposals.saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), any(),
                anyBoolean())).thenReturn(notice);
        assertThat(propose(null)).isSameAs(notice);
        verify(proposals).saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.FOUND),
                argThat(rows -> rows.size() == 1 && rows.getFirst().myDrugId() == 30L), any(), eq(false));
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

    // ---- 2026-11-08 카톡 기록: '나 먹는 약들 3일간 오늘부터 9시·13시·19시 식후 30분 한 알씩, 시럽은 20미리' ----

    private final MedicationTarget vitamin = target(31L, "가상비타민정", null);
    private final MedicationTarget syrup = new MedicationTarget(35L, 10L, "엄마", "PARENT", "가상기침시럽", 1, null, null, null,
            null, LocalDateTime.of(2026, 11, 1, 9, 0), false);

    private QuestionResponse.Answer proposeAll(String text) {
        return service.propose(42L, 7L, 10L, question, text, List.of(), true, ChatDeadline.after(Duration.ofSeconds(5)), false);
    }

    private static ScheduleSetInterpreter.DrugCommand command(long myDrugId, Integer dose, String timing, String amount) {
        return new ScheduleSetInterpreter.DrugCommand(new ParsedCommand("CREATE_SCHEDULE", myDrugId, "2026-11-03", null, 3,
                List.of("09:00", "13:00", "19:00"), 3, dose, timing), amount);
    }

    @Test
    void allDrugsBecomeOneCardWithANoteForTheSyrupAmountAndThePrescription() {
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, vitamin, syrup));
        when(setInterpreter.parse(eq("약목록 전부 오늘부터 3일간 9시, 13시, 19시 식후 30분 한 알씩, 가상기침시럽은 20미리"), eq(TODAY), any()))
                .thenReturn(new ScheduleSetInterpreter.Result("CREATE_SCHEDULE", List.of(command(30L, 1, "식후 30분", null),
                        command(31L, 1, "식후 30분", null), command(35L, 1, "식후 30분", "20mL"))));
        when(proposals.prescriptions(10L, List.of(30L, 31L, 35L))).thenReturn(java.util.Map.of(31L,
                new org.semiproject.pillbadeumserver.image.domain.PrescriptionDose("1", 2, 5)));
        when(proposals.saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), any(), anyBoolean()))
                .thenReturn(notice);
        assertThat(proposeAll("약목록 전부 오늘부터 3일간 9시, 13시, 19시 식후 30분 한 알씩, 가상기침시럽은 20미리")).isSameAs(notice);

        var three = List.of("09:00", "13:00", "19:00");
        var end = TODAY.plusDays(2);
        verify(proposals).saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.foundMany(3, List.of())),
                eq(List.of(
                        new ChatProposalStore.ScheduleRow(30L, new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                                List.of("07:00", "19:00"), 2, "식후 30분"), new ScheduleValues(TODAY, end, three, 1, "식후 30분"),
                                "하루 2번에서 3번으로 바뀌어요."),
                        new ChatProposalStore.ScheduleRow(31L, new ScheduleValues(null, null, null, 2, "식후 30분"),
                                new ScheduleValues(TODAY, end, three, 1, "식후 30분"),
                                "처방전 값: 하루 2번·5일분. 처방과 다르게 먹으려면 의사·약사와 먼저 상의해 주세요."),
                        new ChatProposalStore.ScheduleRow(35L, new ScheduleValues(null, null, null, null, null),
                                new ScheduleValues(TODAY, end, three, 1, "식후 30분 · 1회 20mL"), "1회 20mL는 복용 때 칸에 적었어요."))),
                any(), eq(false));
        // 해석기에는 프로필 이름을 빼고 대상 약만 보낸다(8.4).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MedicationTarget>> sent = ArgumentCaptor.forClass(List.class);
        verify(setInterpreter).parse(anyString(), eq(TODAY), sent.capture());
        assertThat(sent.getValue()).extracting(MedicationTarget::myDrugId).containsExactly(30L, 31L, 35L);
        assertThat(sent.getValue()).allSatisfy(target -> assertThat(target.profileName()).isEmpty());
        verifyNoInteractions(interpreter);
    }

    @Test
    void drugsAlreadyOnTheSameScheduleAreLeftOutAndNamed() {
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, vitamin));
        var keep = new ScheduleSetInterpreter.DrugCommand(new ParsedCommand("CREATE_SCHEDULE", 30L, null, null, null,
                List.of("07:00", "19:00"), null, 2, null), null);
        when(setInterpreter.parse(anyString(), eq(TODAY), any())).thenReturn(new ScheduleSetInterpreter.Result("CREATE_SCHEDULE",
                List.of(keep, command(31L, 1, null, null))));
        proposeAll("전부 바꿔 줘");
        verify(proposals).saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question),
                eq(ScheduleProposalService.foundMany(1, List.of("가상혈압정5밀리그램"))),
                argThat(rows -> rows.size() == 1 && rows.getFirst().myDrugId() == 31L), any(), eq(false));
        assertThat(ScheduleProposalService.foundMany(1, List.of("가상혈압정5밀리그램")))
                .endsWith("\n" + ScheduleProposalService.SAME_SKIPPED + " ‘가상혈압정5밀리그램’.");
    }

    @Test
    void oneDrugOutsideTheRulesStopsTheCardAndNamesThatDrug() {
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, vitamin));
        when(setInterpreter.parse(anyString(), eq(TODAY), any())).thenReturn(new ScheduleSetInterpreter.Result("CREATE_SCHEDULE",
                List.of(command(30L, 1, null, null), command(31L, 11, null, null))));
        proposeAll("전부 바꿔 줘");
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question),
                eq("‘가상비타민정’: 1회 개수는 1~10개로 말해 주세요." + ScheduleProposalService.REJECTED_TAIL), any(), eq(false));
        verify(proposals, never()).saveScheduleSetAnswer(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void anEmptyListOrMoreThanTenDrugsAnswerWithANoticeWithoutReading() {
        when(medications.drugs(7L, 10L)).thenReturn(List.of());
        proposeAll("전부 3일간");
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.NO_DRUGS), any(), eq(false));
        var eleven = new java.util.ArrayList<MedicationTarget>();
        for (long id = 1; id <= 11; id++) eleven.add(target(id, "가상약" + id + "정", null));
        when(medications.drugs(7L, 10L)).thenReturn(eleven);
        proposeAll("전부 3일간");
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.TOO_MANY), any(), eq(false));
        verifyNoInteractions(setInterpreter, proposals);
    }

    @Test
    void namedDrugsMustEachBeOnTheListOrTheBotAsksWhichDrug() {
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, vitamin));
        service.propose(42L, 7L, 10L, question, "혈압약이랑 없는약 바꿔 줘", List.of("가상혈압정5밀리그램", "없는약"), false,
                ChatDeadline.after(Duration.ofSeconds(5)), false);
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.ASK_DRUG), any(), eq(false));
        verifyNoInteractions(setInterpreter, interpreter, proposals);
    }

    @Test
    void anAmountThatDoesNotFitTheTimingFieldIsLeftAsANote() {
        var values = new ScheduleValues(TODAY, TODAY, List.of("09:00"), 1, "식후 30분 미지근한 물과 함께");
        var kept = ScheduleProposalService.withAmount(values, "20mL");
        assertThat(kept.values()).isEqualTo(values);
        assertThat(kept.note()).isEqualTo("1회 20mL는 복용 때 칸(20자)에 다 들어가지 않아 적지 못했어요.");
    }

    // QA 2026-11-08 다시 돌림 시나리오 01: '저녁 약은 7시 말고 9시로 바꿔줘'는 저녁에 먹는 약만(아침에만 먹는 약을 옮기지 않음)
    @Test
    void anEveningGroupIsOnlyTheDrugsTakenInTheEvening() {
        var morning = target(32L, "가상아침정", "08:00");
        when(medications.drugs(7L, 10L)).thenReturn(List.of(pressure, vitamin, morning));
        interpreted(parsed("CREATE_SCHEDULE", null, List.of("21:00"), null));
        when(proposals.saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), anyString(), any(), any(), anyBoolean()))
                .thenReturn(notice);
        assertThat(proposeAll("저녁 약은 7시 말고 9시로 바꿔줘")).isSameAs(notice);
        var month = new ScheduleValues(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), List.of("07:00", "19:00"), 2, "식후 30분");
        verify(proposals).saveScheduleSetAnswer(eq(42L), eq(7L), eq(10L), eq(question), eq(ScheduleProposalService.FOUND),
                eq(List.of(new ChatProposalStore.ScheduleRow(30L, month,
                        new ScheduleValues(month.startDate(), month.endDate(), List.of("07:00", "21:00"), 2, "식후 30분"), null))),
                any(), eq(false));
        verify(interpreter).parse(argThat(request -> request.selectedMyDrugId() == 30L), eq(TODAY), any());
        verifyNoInteractions(setInterpreter);

        when(medications.drugs(7L, 10L)).thenReturn(List.of(vitamin, morning));
        proposeAll("저녁 약은 9시로");
        verify(questions).saveNotice(eq(42L), eq(7L), eq(10L), eq(question),
                eq("저녁에 먹는 일정이 있는 약이 없어요. " + ScheduleProposalService.ASK_DRUG), any(), eq(false));
    }

    // QA 2026-11-08 다시 돌림 시나리오 11: 시럽 1회 20mL를 넣은 뒤 '??'에 이어 만든 카드가 복용 때를 '식후 30분'으로 바꿔 20mL를 지우려 함
    @Test
    void aStoredSyrupAmountStaysUnlessANewAmountIsSaid() {
        var now = new ScheduleValues(TODAY, TODAY, List.of("09:00"), 1, "식후 30분 · 1회 20mL");
        var timingOnly = new ScheduleValues(TODAY, TODAY, List.of("09:00"), 1, "식후 30분");
        var kept = ScheduleProposalService.amounted(now, timingOnly, null);
        assertThat(kept.values()).as("같은 일정이 되어 카드를 만들지 않음").isEqualTo(now);
        assertThat(kept.note()).isNull();
        var before = new ScheduleValues(TODAY, TODAY, List.of("09:00"), 1, "식전");
        assertThat(ScheduleProposalService.amounted(now, before, null).values().timing()).isEqualTo("식전 · 1회 20mL");
        var cleared = new ScheduleValues(TODAY, TODAY, List.of("09:00"), 1, null);
        assertThat(ScheduleProposalService.amounted(now, cleared, null).values().timing()).isEqualTo("1회 20mL");
        assertThat(ScheduleProposalService.amounted(now, now, "10mL").values().timing()).as("새 양은 바꿈")
                .isEqualTo("식후 30분 · 1회 10mL");
        assertThat(ScheduleProposalService.amounted(new ScheduleValues(TODAY, TODAY, List.of("09:00"), 1, "1회 20mL"),
                cleared, "7.5mL").values().timing()).isEqualTo("1회 7.5mL");
        var noAmount = new ScheduleValues(TODAY, TODAY, List.of("09:00"), 1, "식후");
        assertThat(ScheduleProposalService.amounted(noAmount, timingOnly, null).values()).as("양이 없던 약은 그대로")
                .isEqualTo(timingOnly);
        assertThat(ScheduleProposalService.storedAmount("식후 30분 · 1회 20mL")).isEqualTo("20mL");
        assertThat(ScheduleProposalService.storedAmount("식후 1회 20분")).isNull();
    }
}
