package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** CHAT-006·007의 맥락·출처·개인정보 최소화·시간 예산 경계를 외부 호출 없이 확인한다. */
class AnswerContextTests {
    private final JsonMapper json = new JsonMapper();
    private final AnswerSources sources = new AnswerSources(json);
    private SimpleAsyncTaskExecutor executor;
    private AnswerGenerator generator;

    @BeforeEach
    void prepare() {
        executor = new SimpleAsyncTaskExecutor("answer-context-test-");
        executor.setVirtualThreads(true);
        generator = new AnswerGenerator(mock(OpenAiClient.class), mock(OfficialDrugEvidence.class), mock(DurLookupService.class),
                new DurEvaluator(), sources, json, executor);
    }

    @AfterEach
    void close() { executor.close(); }

    @Test
    void recentTurnsKeepTheLatestSixInChronologicalPairsAndRemoveSourceBlocks() {
        List<AnswerInput.Turn> turns = new ArrayList<>();
        for (int i = 1; i <= 8; i++) turns.add(new AnswerInput.Turn(i, "question-" + i,
                "answer-" + i + "\n@@SOURCES@@\n[untrusted-old-source]", 10, 10));
        var messages = generator.recentMessages(turns);
        assertThat(messages).hasSize(12);
        assertThat(messages.getFirst().content()).isEqualTo("question-3");
        assertThat(messages.getLast().content()).isEqualTo("answer-8");
        assertThat(messages).allMatch(message -> !message.content().contains("SOURCES") && !message.content().contains("untrusted"));
    }

    @Test
    void unknownTokenCountsUseAConservativeBudgetAndNeverSplitATurn() {
        var messages = generator.recentMessages(List.of(
                new AnswerInput.Turn(1, "가".repeat(500), "나".repeat(500), null, null),
                new AnswerInput.Turn(2, "다".repeat(500), "라".repeat(500), null, null)));
        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst().content()).startsWith("다");
        assertThat(messages.getLast().content()).startsWith("라");
        assertThat(generator.recentMessages(List.of(new AnswerInput.Turn(3,
                "가".repeat(2000), "answer", null, null)))).isEmpty();
    }

    @Test
    void onlyNamesInCurrentQuestionOrTheLastCompleteTurnArePrioritized() {
        var medications = new ArrayList<AnswerInput.Medication>();
        for (int i = 0; i < 12; i++) medications.add(medication("code-" + i, "등록약" + i + "정"));
        var input = input("등록약 11 정은 언제?", medications, List.of());
        assertThat(generator.selectMedications(input)).extracting(AnswerInput.Medication::itemSeq).containsExactly("code-11");
        input = input("이건?", medications, List.of(new AnswerInput.Turn(4, "등록약10정", "fixture", 10, 10)));
        assertThat(generator.selectMedications(input)).extracting(AnswerInput.Medication::itemSeq).containsExactly("code-10");
    }

    @Test
    void noMentionFallsBackToTenAndRepeatedCodesAreNotCalledTwice() {
        var medications = new ArrayList<AnswerInput.Medication>();
        for (int i = 0; i < 12; i++) medications.add(medication("code-" + i, "등록약" + i + "정"));
        medications.addFirst(medications.getFirst());
        assertThat(generator.selectMedications(input("일반 복약 질문", medications, List.of())))
                .hasSize(10).extracting(AnswerInput.Medication::itemSeq).doesNotHaveDuplicates();
    }

    @Test
    void backgroundUsesAnAgeRangeUnknownPregnancyAndOnlyTodayAndTomorrowSchedules() {
        var medication = medication("123", "등록약정");
        var input = new AnswerInput(2000, "FEMALE", null, "질문", List.of(medication), List.of(), true);
        var messages = generator.messages(input, List.of(medication), new OfficialDrugEvidence.Evidence(List.of()),
                new DurEvaluator().evaluate(input, List.of()).notice());
        var data = json.readTree(messages.getFirst().content().split("\n", 2)[1]);
        assertThat(data.path("patient").path("pregnant").asString()).isEqualTo("UNKNOWN");
        long years = LocalDate.now(TimeZoneConfig.ZONE).getYear() - 2000;
        assertThat(data.path("patient").path("approximateAge").asString()).isEqualTo((years - 1) + "~" + years);
        assertThat(data.path("patient").has("birthYear")).isFalse();
        assertThat(data.path("userSchedules").size()).isEqualTo(4);
        assertThat(data.path("userSchedules").path(0).path("date").asString()).isEqualTo(LocalDate.now(TimeZoneConfig.ZONE).toString());
        assertThat(data.path("userSchedules").path(3).path("date").asString()).isEqualTo(LocalDate.now(TimeZoneConfig.ZONE).plusDays(1).toString());
        assertThat(data.path("dur").path("lookup").asString()).isEqualTo("FAILED");
        assertThat(data.path("dur").path("result").isNull()).isTrue();
        assertThat(messages.getLast().content()).isEqualTo("질문");
    }

    @Test
    void doseDatesAreInclusiveAndOutOfPeriodMedicationHasNoUpcomingSchedule() {
        LocalDate today = LocalDate.now(TimeZoneConfig.ZONE);
        var medication = new AnswerInput.Medication("123", "등록약정", "maker", "OTC", 1, "식후",
                today.minusDays(3), today, List.of(LocalTime.of(7, 0)), today);
        var input = input("질문", List.of(medication), List.of());
        var data = json.readTree(generator.messages(input, List.of(medication), new OfficialDrugEvidence.Evidence(List.of()),
                new DurEvaluator().evaluate(input, List.of()).notice())
                .getFirst().content().split("\n", 2)[1]);
        assertThat(data.path("userSchedules").size()).isEqualTo(1);
    }

    @Test
    void onlyServerSourcesSurviveAndTheCommonParserRoundTripsDatesAndTypes() {
        var source = new AnswerSource("123", "fixture drug", AnswerSource.Type.PERMIT, LocalDate.of(2026, 10, 1), true, true);
        String content = sources.append("답변\n@@SOURCES@@\n[{\"source\":\"fabricated\"}]", List.of(source));
        assertThat(sources.body(content)).isEqualTo("답변");
        assertThat(sources.sources(content)).containsExactly(source);
        assertThat(content).doesNotContain("fabricated");
    }

    @Test
    void malformedLegacySourcesAreExcludedRatherThanInvented() {
        for (String tail : List.of("{", "null", "{}", "[{}]", "[{\"source\":\"fabricated\"}]")) {
            String content = "legacy answer\n@@SOURCES@@\n" + tail;
            assertThat(sources.body(content)).isEqualTo("legacy answer");
            assertThat(sources.sources(content)).isEmpty();
        }
        assertThrows(ExternalApiException.class, () -> sources.append("@@SOURCES@@\n[]", List.of()));
    }

    @Test
    void expiredDeadlinesRejectEvenACompletedTask() {
        var deadline = ChatDeadline.after(Duration.ZERO);
        assertThrows(ExternalApiException.class, () -> deadline.await(CompletableFuture.completedFuture("late answer")));
    }

    private AnswerInput input(String question, List<AnswerInput.Medication> medications, List<AnswerInput.Turn> turns) {
        return new AnswerInput(2000, "FEMALE", false, question, medications, turns, true);
    }

    private AnswerInput.Medication medication(String code, String name) {
        var today = LocalDate.now(TimeZoneConfig.ZONE);
        return new AnswerInput.Medication(code, name, "maker", "OTC", 1, "식후", today.minusDays(1), today.plusDays(1),
                List.of(LocalTime.of(7, 0), LocalTime.of(17, 0)), today);
    }
}
