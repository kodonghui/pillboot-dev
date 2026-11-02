package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.semiproject.pillbadeumserver.common.ai.AiTextResult;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.semiproject.pillbadeumserver.chat.service.ChatStage.*;

/** V3-CHAT-008: 답변을 만드는 동안 실제로 들어선 단계만 차례대로 알리고, 알림이 실패해도 답변은 그대로 만든다. */
class ChatStageTests {
    private static final String CODE = "199900001";
    private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
    private final LocalDate today = LocalDate.now();
    private final JsonMapper json = new JsonMapper();
    private final OpenAiClient ai = mock(OpenAiClient.class);
    private final OfficialDrugEvidence official = mock(OfficialDrugEvidence.class);
    private final DurLookupService dur = mock(DurLookupService.class);
    private final ChatDrugLookup lookup = mock(ChatDrugLookup.class);
    private final List<ChatStage> stages = new CopyOnWriteArrayList<>();

    @BeforeEach
    void fixtures() {
        executor.setVirtualThreads(true);
        when(ai.createTextResult(anyString(), anyList(), anyInt())).thenReturn(new AiTextResult("합성 답변", "fixture"));
        when(lookup.resolve(any(), any())).thenReturn(ChatDrugLookup.Resolution.none());
        when(dur.collect(any(), any())).thenReturn(List.of());
    }

    @AfterEach void close() { executor.close(); }

    @Test
    void medicationQuestionReportsLookupEvidenceAndWritingInOrder() {
        when(official.collect(any(), any(), any())).thenReturn(officialEvidence());
        var result = generator().generate(medicationQuestion("합성약 효능은?"), reporting());
        assertThat(stages).containsExactly(FINDING_DRUG, CHECKING, WRITING);
        assertThat(new AnswerSources(json).body(result.content())).isEqualTo("합성 답변");
    }

    @Test
    void recordQuestionSkipsLookupAndEvidence() {
        var drug = new AnswerInput.Medication("197400040", "인데놀정10mg", "동광제약", "ETC", 1, "식후", today, today.plusDays(7),
                List.of(LocalTime.of(21, 0)), today);
        var input = new AnswerInput(1993, "UNKNOWN", false, "내 원래 저녁 약 이름과 시각을 한 줄로 알려줘", List.of(drug), List.of(), false);
        generator().generate(input, reporting());
        assertThat(stages).containsExactly(WRITING);
        verifyNoInteractions(official, dur, lookup);
    }

    @Test
    void fixedNoticeWithoutOfficialInformationDoesNotClaimTheWritingStage() {
        when(official.collect(any(), any(), any())).thenReturn(new OfficialDrugEvidence.Evidence(List.of()));
        var result = generator().generate(medicationQuestion("합성약 효능은?"), reporting());
        assertThat(stages).containsExactly(FINDING_DRUG, CHECKING);
        assertThat(new AnswerSources(json).body(result.content())).isEqualTo("공식 자료에서 확인할 수 없어요. 약사에게 확인해 주세요.");
        verifyNoInteractions(ai);
    }

    @Test
    void failingListenerDoesNotStopTheAnswer() {
        when(official.collect(any(), any(), any())).thenReturn(officialEvidence());
        var closed = ChatDeadline.after(Duration.ofSeconds(3)).reporting(stage -> { throw new IllegalStateException("closed"); });
        var result = generator().generate(medicationQuestion("합성약 효능은?"), closed);
        assertThat(new AnswerSources(json).body(result.content())).isEqualTo("합성 답변");
    }

    @Test
    void narrowedDeadlineKeepsTheListenerAndPlainDeadlineReportsNothing() {
        reporting().within(Duration.ofSeconds(1)).stage(WRITING);
        ChatDeadline.after(Duration.ofSeconds(1)).stage(CHECKING);
        assertThat(stages).containsExactly(WRITING);
    }

    private ChatDeadline reporting() {
        return ChatDeadline.after(Duration.ofSeconds(3)).reporting(stages::add);
    }

    private AnswerGenerator generator() {
        return new AnswerGenerator(ai, official, dur, new DurEvaluator(), new AnswerSources(json), json, executor, lookup);
    }

    private AnswerInput medicationQuestion(String question) {
        var drug = new AnswerInput.Medication(CODE, "합성약", "", "ETC", null, "", null, null, List.of(), today);
        return new AnswerInput(1993, "FEMALE", false, question, List.of(drug), List.of(), false);
    }

    private OfficialDrugEvidence.Evidence officialEvidence() {
        return new OfficialDrugEvidence.Evidence(List.of(new OfficialDrugEvidence.Lookup(
                new AnswerSource(CODE, "합성약", AnswerSource.Type.PERMIT, today, true, false),
                Map.of("EE_DOC_DATA", "합성 공식 효능"), false)));
    }
}
