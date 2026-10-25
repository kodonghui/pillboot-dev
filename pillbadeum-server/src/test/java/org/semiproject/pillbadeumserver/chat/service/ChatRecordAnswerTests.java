package org.semiproject.pillbadeumserver.chat.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.semiproject.pillbadeumserver.common.ai.*;
import org.semiproject.pillbadeumserver.chat.domain.AnswerSource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatRecordAnswerTests {
    @ParameterizedTest
    @ValueSource(strings={"검색 이야기는 여기까지 하고 원래 등록된 저녁 약 이름과 시각만 알려줘",
            "검색은 그만하고 내가 등록한 약 이름과 시각만 알려줘", "이제 조회하지 말고 내 약 목록과 시각만 알려줘",
            "공공데이터 조회를 중단하고 내 약 이름과 시각만 알려줘"})
    void explicitSearchCancellationDoesNotTurnARecordQuestionIntoAnOfficialLookup(String question) {
        assertThat(ChatQuestionPurpose.recordOnly(question)).isTrue();
    }

    @Test
    void recallingQueriedProductsAgainstTheRegisteredListDoesNotNeedNewOfficialEvidence() {
        assertThat(ChatQuestionPurpose.recordOnly("현재 내 등록 약 두 개와 앞서 조회만 한 제품을 구분해서 짧게 정리해줘")).isTrue();
    }
    @Test
    @SuppressWarnings("unchecked")
    void scheduleAnswerUsesOnlyRegisteredRecordsEvenWhenOfficialProvidersAreUnavailable() {
        var ai=mock(OpenAiClient.class);var official=mock(OfficialDrugEvidence.class);var dur=mock(DurLookupService.class);
        var lookup=mock(ChatDrugLookup.class);var json=new JsonMapper();var sources=new AnswerSources(json);
        try(var executor=new SimpleAsyncTaskExecutor()) {
            executor.setVirtualThreads(true);
            var generator=new AnswerGenerator(ai,official,dur,new DurEvaluator(),sources,json,executor,lookup);
            var day=LocalDate.now();
            var drug=new AnswerInput.Medication("197400040","인데놀정10mg","동광제약","ETC",1,"식후",day,day.plusDays(7),List.of(LocalTime.of(21,0)),day);
            var input=new AnswerInput(1993,"UNKNOWN",false,"내 원래 저녁 약 이름과 시각을 한 줄로 알려줘",List.of(drug),List.of(),false);
            when(ai.createTextResult(anyString(),anyList(),anyInt())).thenReturn(new AiTextResult("인데놀정10mg — 21:00","fixture"));
            var result=generator.generate(input,ChatDeadline.after(Duration.ofSeconds(2)));
            assertThat(result.durNotice()).isNull();assertThat(result.durLookups()).isEmpty();
            assertThat(sources.sources(result.content())).extracting(AnswerSource::source).containsExactly(AnswerSource.Type.USER_INPUT);
            var capture=ArgumentCaptor.forClass(List.class);verify(ai).createTextResult(anyString(),capture.capture(),anyInt());
            var messages=(List<AiMessage>)capture.getValue();
            var bg=json.readTree(messages.getFirst().content().split("\n",2)[1]);
            assertThat(bg.path("recordOnly").asBoolean()).isTrue();
            assertThat(bg.path("officialEvidence").isEmpty()).isTrue();
            assertThat(bg.path("medications").toString()).contains("197400040","21:00");
            verifyNoInteractions(official,dur,lookup);
        }
    }

    @Test
    void directInputMedicationWithoutAnOfficialCodeStillHasReadableScheduleRecords() {
        var ai=mock(OpenAiClient.class);var official=mock(OfficialDrugEvidence.class);var dur=mock(DurLookupService.class);
        var lookup=mock(ChatDrugLookup.class);var json=new JsonMapper();var sources=new AnswerSources(json);
        try(var executor=new SimpleAsyncTaskExecutor()) {
            executor.setVirtualThreads(true);
            var generator=new AnswerGenerator(ai,official,dur,new DurEvaluator(),sources,json,executor,lookup);
            var drug=new AnswerInput.Medication("","직접입력약","","UNKNOWN",null,"식후",null,null,List.of(),LocalDate.now());
            var input=new AnswerInput(1993,"UNKNOWN",false,"내 약 목록의 등록 용량과 기록을 알려줘",List.of(),List.of(),false,List.of(),List.of(),0,List.of("직접입력약"),List.of(drug));
            when(ai.createTextResult(anyString(),anyList(),anyInt())).thenReturn(new AiTextResult("직접입력약 — 복용량 미설정","fixture"));
            var result=generator.generate(input,ChatDeadline.after(Duration.ofSeconds(2)));
            assertThat(sources.body(result.content())).contains("직접입력약","미설정");
            assertThat(sources.sources(result.content())).isEmpty(); // 존재하지 않는 공식 품목코드를 만들지 않는다.
            verifyNoInteractions(official,dur,lookup);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"밥 먹고 몇 분 후에 약 먹어야해?","식전에는 먹으면 안되는거야?","저녁 약 시각과 천식이 있어도 되는지 알려줘",
            "내 약 성분이 겹치는지 알려줘","등록 용량이 안전한지 알려줘","내 저녁 약 두 배는?","내 저녁 약의 약효 지속시간은?","타이레놀 검색해줘",
            "검색은 그만하고 내 저녁 약의 효능과 주의사항을 알려줘", "검색하지 말고 내 저녁 약 두 배 먹어도 돼?",
            "검색은 그만하고 다른 약 타이레놀을 찾아줘"})
    void medicalOrSearchQuestionIsNeverMistakenForARecordOnlyRequest(String question) {
        assertThat(ChatQuestionPurpose.recordOnly(question)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings={"식전에는 먹으면 안되는거야?","밥 먹고 몇 분 후에 약 먹어야해?","저녁 약을 갑자기 끊으면 안 돼?","공복에 먹어도 돼?"})
    void mealAndDiscontinuationQuestionsLoadTheActualUsageAndCautionDocuments(String question) {
        assertThat(OfficialDrugEvidence.wantsDocument("UD_DOC_DATA",question)).isTrue();
        assertThat(OfficialDrugEvidence.wantsDocument("NB_DOC_DATA",question)).isTrue();
    }
}
