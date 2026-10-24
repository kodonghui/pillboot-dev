package org.semiproject.pillbadeumserver.chat.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import org.semiproject.pillbadeumserver.common.ai.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** PR57의 검색 대상·선택·회상 누락을 외부 모델의 비결정성과 분리해 재현한다. */
class ChatLookupReviewTests {
    private static final String A = "199900001", B = "199900002", C = "199900003";
    private final JsonMapper json = new JsonMapper();
    private final OpenAiClient ai = mock(OpenAiClient.class);
    private final PublicDataClient data = mock(PublicDataClient.class);
    private final OfficialDrugEvidence official = mock(OfficialDrugEvidence.class);
    private final DurLookupService dur = mock(DurLookupService.class);
    private final AnswerSources sources = new AnswerSources(json);
    private SimpleAsyncTaskExecutor executor;
    private ChatDrugLookup lookup;
    private AnswerGenerator generator;

    @BeforeEach void setup() {
        executor = new SimpleAsyncTaskExecutor(); executor.setVirtualThreads(true);
        lookup = new ChatDrugLookup(ai, data, sources, executor);
        generator = new AnswerGenerator(ai, official, dur, new DurEvaluator(), sources, json, executor, lookup);
    }
    @AfterEach void close() { executor.close(); }
    private ChatDeadline deadline() { return ChatDeadline.after(Duration.ofSeconds(5)); }
    private Map<String, Object> row(String code, String name) {
        return Map.of("ITEM_SEQ", code, "ITEM_NAME", name, "ENTP_NAME", "시험제약");
    }
    private AnswerInput.Medication medication(String code, String name) {
        return new AnswerInput.Medication(code, name, "시험제약", "UNKNOWN", null, null, null, null, null, LocalDate.now());
    }
    private AnswerSource source(String code, String name) {
        return new AnswerSource(code, name, AnswerSource.Type.PERMIT, LocalDate.now(), true, false);
    }
    private AnswerInput input(String question, List<AnswerInput.Turn> turns, boolean registered) {
        return new AnswerInput(1993, "MALE", false, question,
                registered ? List.of(medication(C, "무관한등록약")) : List.of(), turns, false);
    }
    private AnswerInput.Turn turn(int number, String question, ChatDrugLookup.Resolution reply) {
        return new AnswerInput.Turn(number, question, sources.append(reply.reply(), reply.sources()), null, null);
    }
    private AnswerInput.Turn answered(String question, AnswerSource... provenance) {
        return new AnswerInput.Turn(1, question, sources.append("공식 자료에 따른 설명", List.of(provenance)), null, null);
    }
    private void extract(String mode, String... names) {
        when(ai.createJson(anyString(), anyList(), anyInt(), eq("chat_drug_query"), anyMap(), eq("low")))
                .thenReturn(json.valueToTree(Map.of("mode", mode, "choice", 0, "names",
                        Arrays.stream(names).map(name -> Map.of("name", name, "maker", "")).toList())));
    }
    @SafeVarargs
    private final void rows(Map<String, Object>... rows) {
        when(data.get(anyString(), anyMap(), any(Duration.class)))
                .thenReturn(json.valueToTree(Map.of("totalCount", rows.length, "items", List.of(rows))));
    }
    private void officialByCode() {
        when(data.get(anyString(), anyMap(), any(Duration.class))).thenAnswer(call -> {
            String code = Objects.toString(((Map<?, ?>) call.getArgument(1)).get("item_seq"));
            return json.valueToTree(Map.of("items", List.of(row(code, code.equals(A) ? "가상약정10mg" : "다른약정20mg"))));
        });
    }
    private void generatedEvidence() {
        when(official.collect(anyList(), any(ChatDeadline.class), anyString())).thenAnswer(call -> {
            List<AnswerInput.Medication> meds = call.getArgument(0);
            return new OfficialDrugEvidence.Evidence(meds.stream().map(m -> new OfficialDrugEvidence.Lookup(
                    source(m.itemSeq(), m.name()), Map.of("EE_DOC_DATA", "합성 효능 자료"), false)).toList());
        });
        when(dur.collect(any(AnswerInput.class), any(ChatDeadline.class))).thenAnswer(call -> {
            AnswerInput value = call.getArgument(0);
            return value.medications().stream().flatMap(m ->
                    DurLookupService.requiredTypes(value, value.medications().size(), LocalDate.now()).stream()
                            .map(type -> new DurLookup(m.itemSeq(), type, true, List.<DurLookup.Row>of(), LocalDate.now()))).toList();
        });
        when(ai.createTextResult(anyString(), anyList(), anyInt())).thenReturn(new AiTextResult("합성 답변", "review-test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"표로 정리해줘", "짧게 알려줘", "두 문장으로 요약해줘"})
    void formattingRestoresTheSearchedProductEvenWhenDecisionSaysRecord(String question) {
        for (boolean registered : List.of(false, true)) {
            reset(ai, data, official, dur); extract("FOLLOWUP"); officialByCode(); generatedEvidence();
            var input = input(question, List.of(answered("가상약정10mg 효능은?", source(A, "가상약정10mg"))), registered);
            var result = generator.generate(input, deadline(), new QuestionPlan(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, question));
            assertThat(sources.body(result.content())).isEqualTo("합성 답변");
            verify(official).collect(argThat(m -> m.size() == 1 && m.getFirst().itemSeq().equals(A)), any(ChatDeadline.class), contains("효능"));
            assertThat(sources.sources(result.content()).stream().filter(s -> s.source() == AnswerSource.Type.PERMIT).map(AnswerSource::itemSeq))
                    .containsExactly(A);
        }
    }

    @Test void aListQuestionAboutCoadministrationReachesOfficialEvidenceAndDur() {
        extract("RECORD"); generatedEvidence();
        String question = "내 약 목록 중 같이 먹을 수 있는 약을 정리해줘";
        var input = new AnswerInput(1993, "MALE", false, question, List.of(medication(A, "가상약"), medication(B, "다른약")), List.of(), false);
        generator.generate(input, deadline(), QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, question, List.of()));
        verify(official).collect(argThat(m -> m.size() == 2), any(ChatDeadline.class), eq(question));
        verify(dur).collect(argThat(value -> value.medications().size() == 2 && ChatQuestionPurpose.coadministration(value.question())), any(ChatDeadline.class));
    }

    @Test void explicitlyReturningToRegisteredRecordsDoesNotReuseTheSearchedProduct() {
        when(ai.createTextResult(anyString(), anyList(), anyInt())).thenReturn(new AiTextResult("무관한등록약", "record"));
        var input = input("검색은 그만하고 내 등록 약 목록만 표로 정리해줘",
                List.of(answered("가상약 효능은?", source(A, "가상약"))), true);
        generator.generate(input, deadline());
        verifyNoInteractions(data, official, dur);
        verify(ai).createTextResult(anyString(), argThat(messages -> {
            var background = json.readTree(messages.getFirst().content().split("\n", 2)[1]);
            return background.path("recordOnly").asBoolean() && background.path("registeredItemSeqs").toString().contains(C);
        }), anyInt());
    }

    @Test void formattingAgainRetainsTheOriginalMedicalTopic() {
        officialByCode(); generatedEvidence();
        var history = new ArrayList<AnswerInput.Turn>(); history.add(answered("가상약정10mg의 효능과 주의사항은?", source(A, "가상약정10mg")));
        var first = generator.generate(input("표로 정리해줘", history, false), deadline(),
                new QuestionPlan(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, "표로 정리해줘"));
        history.add(new AnswerInput.Turn(2, "표로 정리해줘", first.content(), null, null));
        clearInvocations(official);
        generator.generate(input("짧게 알려줘", history, false), deadline(),
                new QuestionPlan(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, "짧게 알려줘"));
        verify(official).collect(anyList(), any(ChatDeadline.class), contains("효능과 주의사항"));
    }

    @Test void strengthOnlyRefinementValidatesNameAndNewStrengthSeparately() {
        extract("LOOKUP", "가상약10mg"); rows(row(A, "가상약정10밀리그램"));
        var recent = List.of(answered("가상약 검색해줘", source(A, "가상약정10밀리그램"), source(B, "가상약정20밀리그램")));
        var result = lookup.resolve(input("10mg짜리", recent, false), deadline());
        assertThat(result.reply()).isNull();
        assertThat(result.medications()).extracting(AnswerInput.Medication::itemSeq).containsExactly(A);
        verify(data, atLeastOnce()).get(anyString(), argThat(p -> p.containsKey("item_name")), any(Duration.class));
    }

    @Test void refinementCannotInventANameOrAnUnprovidedStrength() {
        var recent = List.of(answered("가상약 검색해줘", source(A, "가상약정10mg")));
        for (String invented : List.of("없는약10mg", "가상약20mg", "가상약서방정10mg")) {
            reset(ai, data); extract("LOOKUP", invented);
            assertThat(lookup.resolve(input("10mg짜리", recent, false), deadline()).reply()).contains("임의로 고르지");
            verifyNoInteractions(data);
        }
    }

    @Test void coadministrationFollowupRestoresBothSearchedProductsRatherThanRegisteredOnes() {
        for (boolean registered : List.of(false, true)) {
            reset(ai, data, official, dur); extract("FOLLOWUP"); officialByCode(); generatedEvidence();
            var input = input("둘을 같이 먹어도 돼?", List.of(answered("가상약정10mg과 다른약정20mg 효능은?",
                    source(A, "가상약정10mg"), source(B, "다른약정20mg"))), registered);
            generator.generate(input, deadline());
            verify(official).collect(argThat(m -> m.stream().map(AnswerInput.Medication::itemSeq).toList().equals(List.of(A, B))),
                    any(ChatDeadline.class), anyString());
            verify(dur).collect(argThat(value -> value.medications().stream().map(AnswerInput.Medication::itemSeq).toList().containsAll(List.of(A, B))
                    && ChatQuestionPurpose.coadministration(value.question())), any(ChatDeadline.class));
        }
    }

    @Test void conversationRecallWithoutRegisteredDrugsStillReachesTheModel() {
        when(ai.createTextResult(anyString(), anyList(), anyInt())).thenReturn(new AiTextResult("가상약정10mg을 물어보셨어요.", "recall"));
        var input = input("내가 아까 물어본 약 이름은?", List.of(answered("가상약정10mg 효능은?", source(A, "가상약정10mg"))), false);
        var answer = generator.generate(input, deadline());
        assertThat(sources.body(answer.content())).contains("가상약정10mg");
        assertThat(sources.sources(answer.content())).isEmpty();
        verify(ai).createTextResult(anyString(), argThat(messages -> messages.stream().anyMatch(m -> m.content().contains("가상약정10mg"))), anyInt());
        verifyNoInteractions(data, official, dur);
    }

    @Test void summaryOnlyRecallWorksButTrulyEmptyRecordsRemainExplicit() {
        when(ai.createTextResult(anyString(), anyList(), anyInt())).thenReturn(new AiTextResult("가상약정10mg을 물어보셨어요.", "summary-recall"));
        var summary = new AnswerInput.Summary(1, 1, 10, "사용자가 가상약정10mg을 질문함");
        for (boolean similar : List.of(false, true)) {
            var input = new AnswerInput(1993, "MALE", false, "내가 아까 물어본 약 이름은?", List.of(), List.of(), false,
                    similar ? List.of() : List.of(summary), similar ? List.of(summary) : List.of(), 1);
            assertThat(sources.body(generator.generate(input, deadline()).content())).contains("가상약정10mg");
        }
        clearInvocations(ai);
        assertThat(sources.body(generator.generate(input("내 등록 약 이름은?", List.of(), false), deadline()).content()))
                .isEqualTo("등록된 약이 없어요.");
        verifyNoInteractions(ai, data, official, dur);
    }

    @Test void aResolvedFirstProductSurvivesDisambiguatingTheSecondAndDurSeesTheOriginalPair() {
        String original = "가상약정10mg과 다른약을 같이 먹을 수 있어?";
        extract("LOOKUP", "가상약정10mg", "다른약");
        when(data.get(anyString(), anyMap(), any(Duration.class))).thenAnswer(call -> {
            var params = (Map<?, ?>) call.getArgument(1);
            var items = "가상약정10mg".equals(params.get("item_name")) ? List.of(row(A, "가상약정10mg"))
                    : List.of(row(B, "다른약정20mg"), row("199900004", "다른약정40mg"));
            return json.valueToTree(Map.of("totalCount", items.size(), "items", items));
        });
        var pending = lookup.resolve(input(original, List.of(), false), deadline());
        assertThat(pending.reply()).startsWith(ChatDrugLookup.CHOICES);
        assertThat(pending.sources()).extracting(AnswerSource::itemSeq).contains(A, B, "199900004");
        assertThat(pending.sources()).allSatisfy(s -> assertThat(s.ok()).isFalse());
        var recent = List.of(turn(1, original, pending));
        reset(data); officialByCode(); generatedEvidence(); clearInvocations(ai);
        var selectedInput = input("1번", recent, false);
        var result = generator.generate(selectedInput, deadline());
        assertThat(sources.body(result.content())).isEqualTo("합성 답변");
        verify(official).collect(argThat(m -> m.stream().map(AnswerInput.Medication::itemSeq).toList().containsAll(List.of(A, B))
                        && m.size() == 2), any(ChatDeadline.class), contains("같이 먹을"));
        verify(dur).collect(argThat(value -> value.medications().size() == 2
                        && ChatQuestionPurpose.coadministration(value.question())), any(ChatDeadline.class));
        verify(ai).createTextResult(anyString(), argThat(messages -> messages.getLast().content().contains(original)
                && messages.getLast().content().contains("1번")), anyInt());
        assertThat(selectedInput.question()).isEqualTo("1번");
    }

    @Test void anInvalidNumberKeepsTheChoicesAvailableForACorrectedSelection() {
        extract("LOOKUP", "가상약"); rows(row(A, "가상약정10mg"), row(B, "가상약정20mg"));
        var first = lookup.resolve(input("가상약 효능은?", List.of(), false), deadline());
        var recent = new ArrayList<AnswerInput.Turn>(); recent.add(turn(1, "가상약 효능은?", first));
        var invalid = lookup.resolve(input("3번", recent, false), deadline());
        assertThat(invalid.reply()).startsWith(ChatDrugLookup.CHOICES).contains("1.", "2.", "번호");
        assertThat(invalid.sources()).isEqualTo(first.sources());
        recent.add(turn(2, "3번", invalid));
        reset(data); officialByCode(); clearInvocations(ai);
        var corrected = lookup.resolve(input("2번", recent, false), deadline());
        assertThat(corrected.reply()).isNull();
        assertThat(corrected.medications()).extracting(AnswerInput.Medication::itemSeq).containsExactly(B);
        verifyNoInteractions(ai);
    }

    @Test void anAmbiguousFirstProductDoesNotDiscardTheUniqueSecondProduct() {
        extract("LOOKUP", "가상약", "다른약정20mg");
        when(data.get(anyString(), anyMap(), any(Duration.class))).thenAnswer(call -> {
            var params = (Map<?, ?>) call.getArgument(1);
            var items = "가상약".equals(params.get("item_name")) ? List.of(row(A, "가상약정10mg"), row("199900004", "가상약정40mg"))
                    : List.of(row(B, "다른약정20mg"));
            return json.valueToTree(Map.of("totalCount", items.size(), "items", items));
        });
        String question = "가상약과 다른약정20mg을 같이 먹어도 돼?";
        var pending = lookup.resolve(input(question, List.of(), false), deadline());
        assertThat(pending.sources()).extracting(AnswerSource::itemSeq).containsExactly(A, "199900004", B);
        var history = new ArrayList<AnswerInput.Turn>(); history.add(turn(1, question, pending));
        var invalid = lookup.resolve(input("3번", history, false), deadline());
        assertThat(invalid.reply()).startsWith(ChatDrugLookup.CHOICES);
        history.add(turn(2, "3번", invalid));
        reset(data); officialByCode();
        var selected = lookup.resolve(input("1번", history, false), deadline());
        assertThat(selected.medications()).extracting(AnswerInput.Medication::itemSeq).containsExactly(B, A);
    }

    @Test void twoAmbiguousNamesRequestClarificationInsteadOfSilentlyDroppingOne() {
        extract("LOOKUP", "가상약", "다른약"); rows(row(A, "가상약정"), row(B, "다른약정"));
        var result = lookup.resolve(input("가상약과 다른약을 같이 먹어도 돼?", List.of(), false), deadline());
        assertThat(result.medications()).isEmpty();
        assertThat(result.reply()).contains("두 제품 모두", "각각의 정확한 제품명");
    }
}
