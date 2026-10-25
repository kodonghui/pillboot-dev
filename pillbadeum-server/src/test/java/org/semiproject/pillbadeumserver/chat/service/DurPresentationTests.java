package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.common.ai.AiTextResult;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 본문 중복을 줄여도 실제 경고/미확인 결과가 사라지지 않는지 합성 응답으로 검증한다. */
class DurPresentationTests {
    private static final String CODE = "199900001";
    private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
    private final LocalDate today = LocalDate.now();
    private final AnswerSources sources = new AnswerSources(new JsonMapper());

    @AfterEach void close() { executor.close(); }

    private DurLookup success(DurLookup.Type type) { return new DurLookup(CODE, type, true, List.of(), today); }

    private GeneratedAnswer generate(String question, boolean first, List<DurLookup> results) {
        executor.setVirtualThreads(true);
        var ai = mock(OpenAiClient.class);
        var official = mock(OfficialDrugEvidence.class);
        var dur = mock(DurLookupService.class);
        var lookup = mock(ChatDrugLookup.class);
        when(lookup.resolve(any(), any())).thenReturn(ChatDrugLookup.Resolution.none());
        when(official.collect(any(), any(), any())).thenReturn(new OfficialDrugEvidence.Evidence(List.of(
                new OfficialDrugEvidence.Lookup(new AnswerSource(CODE, "합성약", AnswerSource.Type.PERMIT, today, true, false),
                        Map.of("NB_DOC_DATA", "합성 공식 주의사항"), false))));
        when(dur.collect(any(), any())).thenReturn(results);
        when(ai.createTextResult(anyString(), anyList(), anyInt())).thenReturn(new AiTextResult("질문에 대한 합성 답변", "fixture"));
        var drug = new AnswerInput.Medication(CODE, "합성약", "", "ETC", null, "", null, null, List.of(), today);
        var input = new AnswerInput(1993, "FEMALE", true, question, List.of(drug), List.of(), first);
        return new AnswerGenerator(ai, official, dur, new DurEvaluator(), sources, new JsonMapper(), executor, lookup)
                .generate(input, ChatDeadline.after(Duration.ofSeconds(3)));
    }

    @Test void ordinaryCautionQuestionDoesNotReceiveAnUnrelatedNoWarningFooterOrCard() {
        var result = generate("사용할 때 주의사항은?", false, List.of(success(DurLookup.Type.AGE), success(DurLookup.Type.PREGNANCY)));
        assertThat(sources.body(result.content())).isEqualTo("질문에 대한 합성 답변");
        assertThat(result.durNotice()).isNull();
        assertThat(sources.sources(result.content())).anyMatch(s -> s.source() == AnswerSource.Type.DUR);
    }

    @Test void explicitDurQuestionGetsOneCardWithoutTheSameServerFooter() {
        var result = generate("DUR에서 어떤 범위를 확인했어?", false, List.of(success(DurLookup.Type.AGE), success(DurLookup.Type.PREGNANCY)));
        assertThat(result.durNotice().lookup()).isEqualTo("ALL");
        assertThat(result.durNotice().result()).isEqualTo("NONE");
        assertThat(sources.body(result.content())).isEqualTo("질문에 대한 합성 답변");
    }

    @Test void actualWarningRemainsInTheFirstAnswerCardWithItsFullContent() {
        var warning = new DurLookup(CODE, DurLookup.Type.PREGNANCY, true,
                List.of(new DurLookup.Row(null, "합성성분", "", "합성 임부 경고 원문", today)), today);
        var result = generate("임부 금기는?", true, List.of(success(DurLookup.Type.AGE), warning));
        assertThat(result.durNotice().result()).isEqualTo("FOUND");
        assertThat(result.durNotice().items()).singleElement().satisfies(item -> assertThat(item.content()).contains("합성 임부 경고 원문"));
        assertThat(sources.body(result.content())).doesNotContain("DUR 확인:", "합성 임부 경고 원문");
    }

    @Test void partialLookupRetainsTheUnverifiedMedicationWithoutDuplicatingItInTheBody() {
        var result = generate("효능은?", false, List.of(DurLookup.failed(CODE, DurLookup.Type.AGE, today), success(DurLookup.Type.PREGNANCY)));
        assertThat(result.durNotice().lookup()).isEqualTo("PARTIAL");
        assertThat(result.durNotice().uncheckedDrugNames()).containsExactly("합성약");
        assertThat(sources.body(result.content())).isEqualTo("질문에 대한 합성 답변");
    }

    @Test void firstAnswerWithoutACardStillKeepsTheFailedLookupWarningInTheBody() {
        var result = generate("효능은?", true, List.of(DurLookup.failed(CODE, DurLookup.Type.AGE, today), DurLookup.failed(CODE, DurLookup.Type.PREGNANCY, today)));
        assertThat(result.durNotice()).isNull();
        assertThat(sources.body(result.content())).containsOnlyOnce("DUR 확인:").contains("금기가 없다는 뜻은 아니니");
    }
}
