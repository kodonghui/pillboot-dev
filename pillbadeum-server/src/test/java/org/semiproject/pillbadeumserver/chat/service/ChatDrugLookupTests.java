package org.semiproject.pillbadeumserver.chat.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.semiproject.pillbadeumserver.common.ai.OpenAiClient;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatDrugLookupTests {
    private final JsonMapper json=JsonMapper.builder().build();
    private final OpenAiClient ai=mock(OpenAiClient.class);
    private final PublicDataClient data=mock(PublicDataClient.class);
    private final AnswerSources sources=new AnswerSources(json);
    private SimpleAsyncTaskExecutor executor;
    private ChatDrugLookup lookup;
    @BeforeEach void setup() {
        executor=new SimpleAsyncTaskExecutor();executor.setVirtualThreads(true);
        lookup=new ChatDrugLookup(ai,data,sources,executor);
    }
    @AfterEach void close() { executor.close(); }
    private Map<String,Object> row(String code,String name) { return Map.of("ITEM_SEQ",code,"ITEM_NAME",name,"ENTP_NAME","시험제약"); }
    private void extraction(String mode,String name) {
        when(ai.createJson(anyString(),anyList(),anyInt(),eq("chat_drug_query"),anyMap(),eq("low")))
                .thenReturn(json.valueToTree(Map.of("mode",mode,"choice",0,"names",name.isEmpty()?List.of():List.of(Map.of("name",name,"maker","")))));
    }
    private void results(int total,List<Map<String,Object>> rows) {
        when(data.get(anyString(),anyMap(),any(Duration.class))).thenReturn(json.valueToTree(Map.of("totalCount",total,"items",rows)));
    }
    private AnswerInput input(String q,List<AnswerInput.Turn> recent) {
        return new AnswerInput(1993,"UNKNOWN",false,q,List.of(),recent,recent.isEmpty());
    }
    @Test void uniqueOfficialProductCanBeReadWithoutCreatingARegistration() {
        extraction("LOOKUP","가상약정10mg");results(1,List.of(row("199900001","가상약정10밀리그램")));
        var result=lookup.resolve(input("가상약정10mg 효능은?",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.reply()).isNull();assertThat(result.medications()).hasSize(1);
        assertThat(result.medications().getFirst().itemSeq()).isEqualTo("199900001");
        assertThat(result.medications().getFirst().dosePerTime()).isNull();
        assertThat(result.medications().getFirst().doseTimes()).isNull();
    }
    @Test void multipleRealProductsAskRatherThanTakingFirstResult() {
        extraction("LOOKUP","가상약");results(2,List.of(row("199900001","가상약정10밀리그램"),row("199900002","가상약정20밀리그램")));
        var result=lookup.resolve(input("가상약 효능 알려줘",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.medications()).isEmpty();assertThat(result.reply()).startsWith(ChatDrugLookup.CHOICES).contains("1.","2.");
        assertThat(result.sources()).extracting(s->s.itemSeq()).containsExactly("199900001","199900002");
    }
    @Test void secondChoiceUsesStoredServerSearchCodesAndRechecksOfficialCode() {
        extraction("LOOKUP","가상약");results(2,List.of(row("199900001","가상약정10밀리그램"),row("199900002","가상약정20밀리그램")));
        var first=lookup.resolve(input("가상약 효능 알려줘",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        clearInvocations(ai,data);results(1,List.of(row("199900002","가상약정20밀리그램")));
        var recent=List.of(new AnswerInput.Turn(1,"가상약 효능 알려줘",sources.append(first.reply(),first.sources()),null,null));
        var selected=lookup.resolve(input("2번",recent),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(selected.medications().getFirst().itemSeq()).isEqualTo("199900002");
        verifyNoInteractions(ai);verify(data).get(anyString(),argThat(p->"199900002".equals(p.get("item_seq"))),any(Duration.class));
    }
    @Test void aSingleReturnedRowOnATruncatedPageIsNotAUniqueProduct() {
        extraction("LOOKUP","가상약");results(101,List.of(row("199900001","가상약정10밀리그램")));
        var result=lookup.resolve(input("가상약 효능은?",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.medications()).isEmpty();assertThat(result.reply()).contains("이 밖에도");
    }
    @Test void noMatchAsksForCorrectionInsteadOfInventingMedicine() {
        extraction("LOOKUP","없는시험약");results(0,List.of());
        var result=lookup.resolve(input("없는시험약 효능은?",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.reply()).contains("찾지 못했어요");assertThat(result.medications()).isEmpty();
    }
    @Test void aModelInventedSearchNameIsNotSentToTheOfficialProvider() {
        extraction("LOOKUP","다른가상약");
        var result=lookup.resolve(input("없는시험약 효능은?",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.reply()).contains("임의로 고르지");verifyNoInteractions(data);
    }
    @Test void goingBackToEveningRecordsDoesNotKeepAnUnregisteredDrugSelected() {
        var result=lookup.resolve(input("다시 저녁 약 이름과 시각만 알려줘",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result).isEqualTo(ChatDrugLookup.Resolution.none());verifyNoInteractions(ai,data);
    }
    @Test void partialBrandWithStrengthKeepsMatchingVariantsForUserConfirmation() {
        extraction("LOOKUP","가상약10mg");results(2,List.of(row("199900001","가상약정10밀리그램"),row("199900002","가상약캡슐10밀리그램")));
        var result=lookup.resolve(input("가상약10mg 효능은?",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.medications()).isEmpty();assertThat(result.sources()).hasSize(2);
    }
    @Test void exactOwnedProductUsesItsCodeWithoutPublicNameSearch() {
        extraction("LOOKUP","인데놀정10mg");
        var owned = new AnswerInput.Medication("197400040", "인데놀정10mg(프로프라놀롤염산염)", "동광제약", "ETC",
                1, "식후", null, null, List.of(), LocalDate.now());
        var input = new AnswerInput(1993,"UNKNOWN",false,"내 저녁 약 인데놀정10mg의 공식 효능은?",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2))).medications()).containsExactly(owned);
        verifyNoInteractions(data);
    }
    @Test void aBroadSearchStillAsksEvenWhenOneVariantIsRegistered() {
        var owned = new AnswerInput.Medication("199900001", "가상약정10mg", "시험제약", "ETC", null,null,null,null,null,LocalDate.now());
        extraction("LOOKUP","가상약");results(2,List.of(row("199900001","가상약정10mg"),row("199900002","가상약정20mg")));
        var input=new AnswerInput(1993,"UNKNOWN",false,"가상약 검색해줘",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2))).reply()).startsWith(ChatDrugLookup.CHOICES);
    }
    @Test void followupToAFailedOwnedQuestionKeepsItsProductInsteadOfEarlierMorningMedicine() {
        extraction("FOLLOWUP","인데놀정10mg");
        var owned = new AnswerInput.Medication("197400040", "인데놀정10mg", "동광제약", "ETC", null,null,null,null,null,LocalDate.now());
        var input=new AnswerInput(1993,"UNKNOWN",false,"그 약과 심박수의 관계는?",List.of(owned),
                List.of(new AnswerInput.Turn(2,"저녁 인데놀정10mg의 효능",null,null,null)),false);
        assertThat(lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2))).medications()).containsExactly(owned);
        verifyNoInteractions(data);
    }
    @Test void aDifferentReleaseFormContainingAnOwnedNameDoesNotReuseTheOwnedCode() {
        extraction("LOOKUP","가상약정10mg서방정");results(0,List.of());
        var owned=new AnswerInput.Medication("199900001","가상약정10mg","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        var input=new AnswerInput(1993,"UNKNOWN",false,"가상약정10mg서방정 효능은?",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2))).medications()).isEmpty();
        verify(data,atLeastOnce()).get(anyString(),anyMap(),any(Duration.class));
    }
}
