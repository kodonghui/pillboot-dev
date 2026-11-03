package org.semiproject.pillbadeumserver.chat.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.common.ai.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 약 이름 없는 의약 후속 질문이 직전 턴의 등록 약을 이어받는지 확인함(2026-11-04 공개 웹 재현). */
class ChatFollowUpTargetTests {
    private static final String A = "199900001", B = "199900002", C = "199900003", D = "199900009";
    private static final String MEDICATIONS = "내가 먹는 약들 병용 가능해?", PREGNANCY = "임신했을때 먹어도 돼?",
            MINE = "내가 먹는 약들 말이야", ASK = "어느 제품을 말씀하시는지";
    private final JsonMapper json = new JsonMapper();
    private final OpenAiClient ai = mock(OpenAiClient.class);
    private final PublicDataClient data = mock(PublicDataClient.class);
    private final OfficialDrugEvidence official = mock(OfficialDrugEvidence.class);
    private final DurLookupService dur = mock(DurLookupService.class);
    private final AnswerSources sources = new AnswerSources(json);
    private final List<AnswerInput.Medication> registered = List.of(
            medication(A, "가상약정10mg"), medication(B, "다른약정20mg"), medication(C, "세번째약정5mg"));
    private SimpleAsyncTaskExecutor executor;
    private ChatDrugLookup lookup;
    private AnswerGenerator generator;

    @BeforeEach void setup() {
        executor = new SimpleAsyncTaskExecutor(); executor.setVirtualThreads(true);
        lookup = new ChatDrugLookup(ai, data, sources, executor);
        generator = new AnswerGenerator(ai, official, dur, new DurEvaluator(), sources, json, executor, lookup);
        generatedEvidence();
    }
    @AfterEach void close() { executor.close(); }

    private static AnswerInput.Medication medication(String code, String name) {
        return new AnswerInput.Medication(code, name, "시험제약", "UNKNOWN", null, null, null, null, null, LocalDate.now());
    }
    private ChatDeadline deadline() { return ChatDeadline.after(Duration.ofSeconds(5)); }
    private AnswerInput input(String question, List<AnswerInput.Turn> turns) {
        return new AnswerInput(1993, "MALE", false, question, registered, turns, turns.isEmpty());
    }
    /** 실제 분류기처럼 직전 두 질문을 넘겨 서버 보정(QuestionPlan.guarded)을 거친 계획을 만든다. */
    private QuestionPlan plan(String question, List<AnswerInput.Turn> turns) {
        var previous = turns.stream().skip(Math.max(0, turns.size() - 2L)).map(AnswerInput.Turn::question).toList();
        return QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.COMBINED, question, previous);
    }
    private void extract(String mode, String... names) {
        when(ai.createJson(anyString(), anyList(), anyInt(), eq("chat_drug_query"), anyMap(), eq("low")))
                .thenReturn(json.valueToTree(Map.of("mode", mode, "choice", 0, "names",
                        Arrays.stream(names).map(name -> Map.of("name", name, "maker", "")).toList())));
    }
    private void generatedEvidence() {
        when(official.collect(anyList(), any(ChatDeadline.class), anyString())).thenAnswer(call -> {
            List<AnswerInput.Medication> meds = call.getArgument(0);
            return new OfficialDrugEvidence.Evidence(meds.stream().map(m -> new OfficialDrugEvidence.Lookup(
                    new AnswerSource(m.itemSeq(), m.name(), AnswerSource.Type.PERMIT, LocalDate.now(), true, false),
                    Map.of("NB_DOC_DATA", "합성 주의 자료"), false)).toList());
        });
        when(dur.collect(any(AnswerInput.class), any(ChatDeadline.class))).thenAnswer(call -> {
            AnswerInput value = call.getArgument(0);
            return value.medications().stream().flatMap(m ->
                    DurLookupService.requiredTypes(value, value.medications().size(), LocalDate.now()).stream()
                            .map(type -> new DurLookup(m.itemSeq(), type, true, List.<DurLookup.Row>of(), LocalDate.now()))).toList();
        });
        when(ai.createTextResult(anyString(), anyList(), anyInt())).thenReturn(new AiTextResult("합성 답변", "follow-up-test"));
    }
    private List<String> codes(List<AnswerInput.Medication> medications) {
        return medications.stream().map(AnswerInput.Medication::itemSeq).toList();
    }
    /** 1턴 '내가 먹는 약들 병용 가능해?'를 실제 생성 흐름으로 답해 근거 블록까지 남긴 이력을 만든다. */
    private List<AnswerInput.Turn> afterRegisteredCoadministration() {
        extract("FOLLOWUP");
        var first = generator.generate(input(MEDICATIONS, List.of()), deadline(), plan(MEDICATIONS, List.of()));
        assertThat(sources.sources(first.content()).stream().filter(s -> s.source() == AnswerSource.Type.PERMIT)
                .map(AnswerSource::itemSeq)).containsExactly(A, B, C);
        clearInvocations(ai, data, official, dur);
        var history = new ArrayList<AnswerInput.Turn>();
        history.add(new AnswerInput.Turn(1, MEDICATIONS, first.content(), null, null));
        return history;
    }

    @Test void pregnancyFollowUpKeepsAllThreeRegisteredDrugsFromThePreviousTurn() {
        var history = afterRegisteredCoadministration();

        var second = generator.generate(input(PREGNANCY, history), deadline(), plan(PREGNANCY, history));
        assertThat(sources.body(second.content())).isEqualTo("합성 답변").doesNotContain(ASK);
        verify(official).collect(argThat(m -> codes(m).equals(List.of(A, B, C))), any(ChatDeadline.class), contains("임신"));
        verify(dur).collect(argThat(value -> codes(value.medications()).containsAll(List.of(A, B, C))
                && DurLookupService.requiredTypes(value, 3, LocalDate.now()).contains(DurLookup.Type.PREGNANCY)), any(ChatDeadline.class));
        verifyNoInteractions(data);
        history.add(new AnswerInput.Turn(2, PREGNANCY, second.content(), null, null));
        clearInvocations(ai, official, dur);

        var third = generator.generate(input(MINE, history), deadline(), plan(MINE, history));
        assertThat(sources.body(third.content())).isEqualTo("합성 답변");
        verify(official).collect(argThat(m -> codes(m).equals(List.of(A, B, C))), any(ChatDeadline.class), contains("임신"));
    }

    @Test void formattingAfterTheThreeDrugAnswerRestoresThoseDrugsAndTheOriginalTopic() {
        var history = afterRegisteredCoadministration();
        String question = "표로 정리해줘";
        var answer = generator.generate(input(question, history), deadline(),
                new QuestionPlan(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, question));
        assertThat(sources.body(answer.content())).doesNotContain(ASK);
        verify(official).collect(argThat(m -> codes(m).equals(List.of(A, B, C))), any(ChatDeadline.class), contains("병용"));
    }

    @Test void followUpNamingADifferentDrugDoesNotPickUpThePreviousDrugs() {
        var history = afterRegisteredCoadministration();
        String question = "가상신약정50mg은 임신했을때 먹어도 돼?";
        extract("LOOKUP", "가상신약정50mg");
        when(data.get(anyString(), anyMap(), any(Duration.class))).thenReturn(json.valueToTree(Map.of("totalCount", 1,
                "items", List.of(Map.of("ITEM_SEQ", D, "ITEM_NAME", "가상신약정50밀리그램", "ENTP_NAME", "시험제약")))));
        generator.generate(input(question, history), deadline(), plan(question, history));
        verify(official).collect(argThat(m -> codes(m).equals(List.of(D))), any(ChatDeadline.class), contains("임신"));
    }

    @Test void recordQuestionAfterTheMedicalFollowUpDoesNotDragTheMedicalTopicOrDrugLookup() {
        var history = afterRegisteredCoadministration();
        history.add(new AnswerInput.Turn(2, PREGNANCY, sources.append("합성 답변", List.of()), null, null));
        String question = "내 약 목록만 알려줘";
        var plan = plan(question, history);
        assertThat(plan.purpose()).isEqualTo(QuestionPlan.Purpose.RECORD);
        assertThat(plan.evidenceQuestion()).isEqualTo(question);
        generator.generate(input(question, history), deadline(), plan);
        verifyNoInteractions(data, official, dur);
        verify(ai, never()).createJson(anyString(), anyList(), anyInt(), eq("chat_drug_query"), anyMap(), anyString());
    }

    @Test void firstQuestionOfARoomUsesTheRegisteredDrugsWithoutAskingForAName() {
        extract("FOLLOWUP");
        var answer = generator.generate(input(PREGNANCY, List.of()), deadline(), plan(PREGNANCY, List.of()));
        assertThat(sources.body(answer.content())).isEqualTo("합성 답변");
        verify(official).collect(argThat(m -> codes(m).equals(List.of(A, B, C))), any(ChatDeadline.class), eq(PREGNANCY));
        verifyNoInteractions(data);
    }

    @Test void threeEarlierProductsThatAreNotAllRegisteredStillAskForTheProductName() {
        extract("FOLLOWUP");
        var provenance = List.of(A, B, D).stream().map(code ->
                new AnswerSource(code, code, AnswerSource.Type.PERMIT, LocalDate.now(), true, false)).toList();
        var history = List.of(new AnswerInput.Turn(1, "세 약 효능은?", sources.append("공식 자료에 따른 설명", provenance), null, null));
        var result = lookup.resolve(input(PREGNANCY, history), deadline());
        assertThat(result.medications()).isEmpty();
        assertThat(result.reply()).contains(ASK);
        verifyNoInteractions(data);
    }
}
