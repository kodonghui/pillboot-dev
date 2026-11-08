package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.semiproject.pillbadeumserver.common.ai.AiMessage;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V3-CHAT-001(AI 호출 8.1)의 요청 모양·서버 확인·실패 처리를 가짜 AI로 검증한다.
 * 실제 모델이 예시 문장을 어떻게 가르는지(분류 정확도)는 이 시험의 범위가 아니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class QuestionIntentReaderTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final OpenAiClient ai = mock(OpenAiClient.class);
    private SimpleAsyncTaskExecutor executor;
    private QuestionIntentReader reader;

    @BeforeEach
    void setup() {
        executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        reader = new QuestionIntentReader(ai, executor);
    }

    @AfterEach
    void close() { executor.close(); }

    private void answer(String intent, List<?> mentions, String target) {
        when(ai.createJson(anyString(), anyList(), anyInt(), eq("question_intent"), anyMap(), eq("low")))
                .thenReturn(json.valueToTree(Map.of("intent", intent, "drugMentions", mentions, "targetMyDrugName", target)));
    }

    // QA 2026-11-05: '일정 잡아줘'가 AI까지 가지 못해 '직접 등록할 수 없어요'로 답했음.
    @ParameterizedTest
    @ValueSource(strings = {"노바스크정 내일부터 일주일 동안 아침 8시에 식후 한 알로 일정 잡아줘", "혈압약 일정 만들어줘",
            "혈압약 일정 짜 줘", "노바스크 아침 8시에 먹게 해줘", "노바스크 알람 맞춰줘", "노바스크 아침 식후 한 알"})
    void asksForAScheduleInOtherWordsReachTheAi(String question) {
        answer("SCHEDULE_CHANGE", List.of(), "노바스크");
        assertThat(read(question, List.of("노바스크")).kind()).isEqualTo(QuestionIntent.Kind.SCHEDULE_CHANGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"노바스크 먹어도 돼?", "오늘 먹을 약 뭐야?", "그럼 내일 두 알 먹으면 되지?"})
    void plainMedicationQuestionsStillSkipTheAi(String question) {
        assertThat(read(question, List.of("노바스크")).kind()).isEqualTo(QuestionIntent.Kind.QUESTION);
        verifyNoInteractions(ai);
    }

    private QuestionIntent read(String question, List<String> names) {
        return reader.read(question, names, ChatDeadline.after(Duration.ofSeconds(5)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void registrationRequestKeepsTheNamesWrittenInTheQuestion(CapturedOutput output) {
        answer("REGISTER_DRUG", List.of("타이레놀 500"), "");
        var intent = read("타이레놀 500 등록해 줘", List.of("가상혈압정 5mg"));
        assertThat(intent.kind()).isEqualTo(QuestionIntent.Kind.REGISTER_DRUG);
        assertThat(intent.drugMentions()).containsExactly("타이레놀 500");
        assertThat(intent.targetMyDrugName()).isNull();

        ArgumentCaptor<List<AiMessage>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        verify(ai).createJson(instructions.capture(), messages.capture(), eq(0), eq("question_intent"), anyMap(), eq("low"));
        assertThat(instructions.getValue()).contains("REGISTER_DRUG", "SCHEDULE_CHANGE", "판단이 애매하면 QUESTION",
                "myDrugNames에 없는 약을 앞으로", "번호만으로 약을 정하지 않는다");
        var sent = json.readTree(messages.getValue().getFirst().content());
        assertThat(sent.path("question").asString()).isEqualTo("타이레놀 500 등록해 줘");
        assertThat(sent.path("myDrugNames").path(0).asString()).isEqualTo("가상혈압정 5mg");
        // 2026-11-08: 앞 대화(recentTurns)만 더한다. 회원·프로필 정보는 보내지 않는다.
        assertThat(sent.properties()).hasSize(3);
        assertThat(sent.path("recentTurns").isArray()).isTrue();
        assertThat(sent.path("recentTurns")).isEmpty();
        // 질문 글·약 이름은 로그에 남기지 않는다.
        assertThat(output).contains("[질문 의도] intent=REGISTER_DRUG mentions=1").doesNotContain("타이레놀");
    }

    @Test
    void drugQuestionWithoutRequestWordsSkipsTheAi() {
        var intent = read("타이레놀 먹어도 돼?", List.of());
        assertThat(intent).isEqualTo(QuestionIntent.question());
        verifyNoInteractions(ai);
    }

    @Test
    void questionAboutRegisteredDrugsStaysAQuestion() {
        answer("QUESTION", List.of("타이레놀"), "");
        var intent = read("내가 등록한 약에 타이레놀 있어?", List.of());
        assertThat(intent).isEqualTo(QuestionIntent.question());
    }

    @Test
    void aiFailureFallsBackToQuestion(CapturedOutput output) {
        when(ai.createJson(anyString(), anyList(), anyInt(), eq("question_intent"), anyMap(), eq("low")))
                .thenThrow(ExternalApiException.failed("OpenAI", "실패", null));
        assertThat(read("타이레놀 등록해 줘", List.of())).isEqualTo(QuestionIntent.question());
        assertThat(output).contains("[질문 의도] 가르지 못해 약 질문으로 둠").doesNotContain("타이레놀");
    }

    @Test
    void aiTimeoutFallsBackToQuestionWithinTheDeadline() {
        when(ai.createJson(anyString(), anyList(), anyInt(), eq("question_intent"), anyMap(), eq("low")))
                .thenAnswer(call -> {
                    Thread.sleep(2_000);
                    return json.valueToTree(Map.of("intent", "REGISTER_DRUG", "drugMentions", List.of("타이레놀"),
                            "targetMyDrugName", ""));
                });
        long start = System.nanoTime();
        var intent = reader.read("타이레놀 등록해 줘", List.of(), ChatDeadline.after(Duration.ofMillis(200)));
        assertThat(intent).isEqualTo(QuestionIntent.question());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }

    // 2026-11-04 운영 카톡: 검색 후보 '1번'을 약목록 첫 약으로 읽어 다른 약의 일정 제안을 만듦
    @Test
    void aTargetChosenOnlyByACandidateNumberIsDropped() {
        var names = List.of("솔로가상정(성분)", "노바가상정10밀리그램");
        var byNumber = json.valueToTree(Map.of("intent", "SCHEDULE_CHANGE", "drugMentions", List.of(),
                "targetMyDrugName", "솔로가상정(성분)"));
        assertThat(QuestionIntentReader.checked(byNumber, "1번 12시에 한알 먹을거라고", names).targetMyDrugName()).isNull();
        // 이름을 말했거나 번호가 먹는 횟수면 그대로
        assertThat(QuestionIntentReader.checked(byNumber, "솔로가상 1번 약 12시로 바꿔 줘", names).targetMyDrugName())
                .isEqualTo("솔로가상정(성분)");
        assertThat(QuestionIntentReader.checked(byNumber, "하루 2번 12시랑 6시에 먹을게", names).targetMyDrugName())
                .isEqualTo("솔로가상정(성분)");
        assertThat(QuestionIntentReader.checked(byNumber, "12시에 한 알 먹을게", names).targetMyDrugName())
                .isEqualTo("솔로가상정(성분)");
    }

    @Test
    void malformedResultsFallBackToQuestion() {
        String question = "타이레놀 등록해 줘";
        var unknown = json.valueToTree(Map.of("intent", "DELETE_DRUG", "drugMentions", List.of(), "targetMyDrugName", ""));
        var notArray = json.valueToTree(Map.of("intent", "REGISTER_DRUG", "drugMentions", "타이레놀", "targetMyDrugName", ""));
        var notText = json.valueToTree(Map.of("intent", "REGISTER_DRUG", "drugMentions", List.of(1), "targetMyDrugName", ""));
        var tooLong = json.valueToTree(Map.of("intent", "REGISTER_DRUG", "drugMentions", List.of("가".repeat(101)),
                "targetMyDrugName", ""));
        var noTarget = json.valueToTree(Map.of("intent", "SCHEDULE_CHANGE", "drugMentions", List.of()));
        for (var result : List.of(unknown, notArray, notText, tooLong, noTarget)) {
            assertThat(QuestionIntentReader.checked(result, question, List.of())).isEqualTo(QuestionIntent.question());
        }
    }

    @Test
    void namesNotInTheQuestionAreDroppedAndOnlyTheFirstTenAreKept() {
        var names = new ArrayList<String>();
        for (int i = 1; i <= 12; i++) names.add("가상약" + i + "정");
        String question = String.join(", ", names) + " 등록해 줘";
        var mentions = new ArrayList<Object>(names);
        mentions.add(1, "아세트아미노펜");
        mentions.add(2, "가상약1정");
        mentions.add(3, " ");
        answer("REGISTER_DRUG", mentions, "");
        var intent = read(question, List.of());
        assertThat(intent.kind()).isEqualTo(QuestionIntent.Kind.REGISTER_DRUG);
        assertThat(intent.drugMentions()).hasSize(10).doesNotContain("아세트아미노펜", "가상약11정", "가상약12정");
        assertThat(intent.drugMentions()).startsWith("가상약1정", "가상약2정");
    }

    @Test
    void registrationWithoutAnyNameStaysARegistrationRequest() {
        answer("REGISTER_DRUG", List.of(), "");
        var intent = read("약 하나 등록해 줘", List.of());
        assertThat(intent.kind()).isEqualTo(QuestionIntent.Kind.REGISTER_DRUG);
        assertThat(intent.drugMentions()).isEmpty();
    }

    @Test
    void scheduleTargetMustBeOneOfTheProfileDrugs() {
        answer("SCHEDULE_CHANGE", List.of(), "가상혈압정 5mg");
        var known = read("혈압약 아침 8시로 바꿔 줘", List.of("가상혈압정 5mg", "가상비타민정"));
        assertThat(known.kind()).isEqualTo(QuestionIntent.Kind.SCHEDULE_CHANGE);
        assertThat(known.targetMyDrugName()).isEqualTo("가상혈압정 5mg");
        assertThat(known.drugMentions()).isEmpty();

        var unknown = read("혈압약 아침 8시로 바꿔 줘", List.of("가상비타민정"));
        assertThat(unknown.kind()).isEqualTo(QuestionIntent.Kind.SCHEDULE_CHANGE);
        assertThat(unknown.targetMyDrugName()).isNull();
    }

    // QA 2026-11-04: '오늘부터 5일간 점심에 먹을거니까'처럼 앞으로 먹을 때를 말하면 일정 요청으로 가를 수 있게 AI에 보낸다
    @Test
    void sayingWhenToTakeADrugIsSentToTheAi() {
        answer("SCHEDULE_CHANGE", List.of(), "가상혈압정 5mg");
        for (String q : List.of("가상혈압정 오늘부터 5일간 점심에 먹을거야", "가상혈압정 점심 12시 한알", "가상혈압정 저녁에 두알씩 먹을게요")) {
            assertThat(read(q, List.of("가상혈압정 5mg")).kind()).as(q).isEqualTo(QuestionIntent.Kind.SCHEDULE_CHANGE);
        }
        verify(ai, org.mockito.Mockito.times(3)).createJson(anyString(), anyList(), anyInt(), eq("question_intent"), anyMap(), eq("low"));
    }

    @Test
    void askingWhetherToTakeStillSkipsTheAi() {
        for (String q : List.of("타이레놀 같이 먹을 수 있어?", "점심에 먹어도 돼?", "타이레놀 몇 알 먹어야 해?")) {
            assertThat(read(q, List.of())).as(q).isEqualTo(QuestionIntent.question());
        }
        verifyNoInteractions(ai);
    }

    // ---- 2026-11-08 카톡 기록: 짧은 이어 말('약목록 전부', '등록해줘')을 앞 대화와 함께 가름 ----

    private QuestionIntent read(String question, List<String> names, List<RecentTurn> recent) {
        return reader.read(question, names, recent, ChatDeadline.after(Duration.ofSeconds(5)));
    }

    private void answerWith(Map<String, Object> result) {
        when(ai.createJson(anyString(), anyList(), anyInt(), eq("question_intent"), anyMap(), eq("low")))
                .thenReturn(json.valueToTree(result));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aShortFollowUpAfterAScheduleTalkReachesTheAiWithTheRecentTurns() {
        var recent = List.of(new RecentTurn(true, "나 먹는 약들 3일간 등록해줘 오늘부터"),
                new RecentTurn(false, ScheduleProposalService.ASK_DRUG));
        answerWith(Map.of("intent", "SCHEDULE_CHANGE", "drugMentions", List.of(), "targetMyDrugNames", List.of(),
                "allMyDrugs", true, "request", "약목록 전부 오늘부터 3일간 일정 넣어 줘"));
        var intent = read("약목록 전부", List.of("가상셀코정", "가상기침시럽"), recent);
        assertThat(intent.kind()).isEqualTo(QuestionIntent.Kind.SCHEDULE_CHANGE);
        assertThat(intent.allMyDrugs()).isTrue();
        assertThat(intent.targetMyDrugName()).isNull();
        assertThat(intent.request()).isEqualTo("약목록 전부 오늘부터 3일간 일정 넣어 줘");

        ArgumentCaptor<List<AiMessage>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        verify(ai).createJson(instructions.capture(), messages.capture(), eq(0), eq("question_intent"), anyMap(), eq("low"));
        assertThat(instructions.getValue()).contains("recentTurns", "약봉이가 예로 든 약 이름", "날짜로 바꾸거나 말하지 않은 값을 채우지 않는다");
        var sent = json.readTree(messages.getValue().getFirst().content());
        assertThat(sent.path("recentTurns")).hasSize(2);
        assertThat(sent.path("recentTurns").path(0).path("user").asBoolean()).isTrue();
        assertThat(sent.path("recentTurns").path(1).path("user").asBoolean()).isFalse();
    }

    @Test
    void aRestatedRequestWithNumbersTheMemberNeverSaidIsDropped() {
        var recent = List.of(new RecentTurn(true, "나 먹는 약들 3일간 등록해줘 오늘부터"));
        answerWith(Map.of("intent", "SCHEDULE_CHANGE", "drugMentions", List.of(), "targetMyDrugNames", List.of(),
                "allMyDrugs", true, "request", "11월 8일부터 10일까지 9시에 넣어 줘"));
        var intent = read("전부", List.of("가상셀코정"), recent);
        assertThat(intent.allMyDrugs()).isTrue();
        assertThat(intent.request()).isNull();

        assertThat(QuestionIntentReader.grounded("오늘부터 3일간 아침 점심 저녁 1알씩", "3일간 아침 점심 저녁 한알씩", List.of())).isTrue();
        assertThat(QuestionIntentReader.grounded("9시, 13시, 19시 식후 30분, 가상기침시럽은 20mL",
                "식후 30분. 9시, 13시, 19시\n20미리라고", List.of())).isTrue();
        assertThat(QuestionIntentReader.grounded("가상정10mg 9시", "9시", List.of("가상정10mg"))).isTrue();
        assertThat(QuestionIntentReader.grounded("8시", "9시", List.of())).isFalse();
    }

    @Test
    void namesOnlyTheBotWroteAreNotRegistered() {
        var recent = List.of(new RecentTurn(true, "가상스텐캡슐 200mg"), new RecentTurn(false,
                "가상스텐캡슐 200mg은 시험제약의 제품이에요. 등록하려면 “가상스텐캡슐 200mg 등록해 줘”라고 말씀해 주세요. 예) 타이레놀 500 등록해 줘"));
        answerWith(Map.of("intent", "REGISTER_DRUG", "drugMentions", List.of("가상스텐캡슐 200mg", "타이레놀 500"),
                "targetMyDrugNames", List.of(), "allMyDrugs", false, "request", "가상스텐캡슐 200mg 등록해 줘"));
        var intent = read("등록해줘", List.of(), recent);
        assertThat(intent.kind()).isEqualTo(QuestionIntent.Kind.REGISTER_DRUG);
        assertThat(intent.drugMentions()).containsExactly("가상스텐캡슐 200mg");
        assertThat(intent.request()).isNull();
    }

    @Test
    void anUnrelatedQuestionAfterAPlainAnswerStillSkipsTheAi() {
        var recent = List.of(new RecentTurn(true, "가상셀코정 먹어도 돼?"), new RecentTurn(false, "공식 자료에서 확인할 수 없어요."));
        assertThat(read("부작용은?", List.of("가상셀코정"), recent)).isEqualTo(QuestionIntent.question());
        verifyNoInteractions(ai);
        assertThat(QuestionIntentReader.followsTask(List.of(new RecentTurn(false, "약목록에 6개를 넣었어요.")))).isTrue();
        assertThat(QuestionIntentReader.followsTask(List.of())).isFalse();
    }

    @Test
    void severalNamedTargetsOnTheListAreKept() {
        answerWith(Map.of("intent", "SCHEDULE_CHANGE", "drugMentions", List.of(),
                "targetMyDrugNames", List.of("가상셀코정", "없는정", "가상기침시럽"), "allMyDrugs", false,
                "request", "가상셀코정이랑 가상기침시럽 아침 8시로 바꿔 줘"));
        var intent = read("가상셀코정이랑 가상기침시럽 아침 8시로 바꿔 줘", List.of("가상셀코정", "가상기침시럽"), List.of());
        assertThat(intent.targetMyDrugNames()).containsExactly("가상셀코정", "가상기침시럽");
        assertThat(intent.targetMyDrugName()).isNull();
        assertThat(intent.request()).isEqualTo("가상셀코정이랑 가상기침시럽 아침 8시로 바꿔 줘");
    }
}
