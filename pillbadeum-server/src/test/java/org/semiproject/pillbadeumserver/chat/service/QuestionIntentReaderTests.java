package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        assertThat(instructions.getValue()).contains("REGISTER_DRUG", "SCHEDULE_CHANGE", "판단이 애매하면 QUESTION");
        var sent = json.readTree(messages.getValue().getFirst().content());
        assertThat(sent.path("question").asString()).isEqualTo("타이레놀 500 등록해 줘");
        assertThat(sent.path("myDrugNames").path(0).asString()).isEqualTo("가상혈압정 5mg");
        assertThat(sent.properties()).hasSize(2);
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
}
