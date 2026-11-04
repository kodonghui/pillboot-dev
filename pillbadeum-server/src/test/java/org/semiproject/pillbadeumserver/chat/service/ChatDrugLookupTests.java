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
    @Test void medicalQuestionAboutMyMedicationsDoesNotKeepThePreviousSingleProductSelected() {
        extraction("FOLLOWUP", "");
        var old = List.of(new org.semiproject.pillbadeumserver.chat.domain.AnswerSource("199900001", "가상약",
                org.semiproject.pillbadeumserver.chat.domain.AnswerSource.Type.PERMIT, LocalDate.now(), true, false));
        var recent = List.of(new AnswerInput.Turn(1, "가상약의 효능은?", sources.append("합성 설명", old), null, null));
        var result = lookup.resolve(input("내 약 식전으로 바꿔도 되는지는 공식 자료로 확인해야 하는 거야?", recent), ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result).isEqualTo(ChatDrugLookup.Resolution.none());
        verifyNoInteractions(data);
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
    // QA 2026-11-04: 등록한 '노바스크정10밀리그램'을 '노바스크'로 물으면 공공 검색 후보 고르기가 나옴
    @Test void abbreviatedNameUsesTheOneOwnedProduct() {
        extraction("LOOKUP","노바스크");
        var owned=new AnswerInput.Medication("199900010","노바스크정10밀리그램(암로디핀베실산염)","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        var input=new AnswerInput(1993,"UNKNOWN",false,"노바스크 효능은?",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2))).medications()).containsExactly(owned);
        verifyNoInteractions(data);
    }
    @Test void abbreviatedNameWithStrengthMustMatchTheOwnedStrength() {
        var owned=new AnswerInput.Medication("199900001","타이레놀정500밀리그람(아세트아미노펜)","시험제약","OTC",null,null,null,null,null,LocalDate.now());
        extraction("LOOKUP","타이레놀 500");
        var same=new AnswerInput(1993,"UNKNOWN",false,"타이레놀 500 효능은?",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(same,ChatDeadline.after(Duration.ofSeconds(2))).medications()).containsExactly(owned);
        verifyNoInteractions(data);
        extraction("LOOKUP","타이레놀 650");results(0,List.of());
        var other=new AnswerInput(1993,"UNKNOWN",false,"타이레놀 650 효능은?",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(other,ChatDeadline.after(Duration.ofSeconds(2))).medications()).isEmpty();
        verify(data,atLeastOnce()).get(anyString(),anyMap(),any(Duration.class));
    }
    // Codex 리뷰: '가상약 5mg'가 숫자만 같은 '가상약정5mcg'로 바로 답하던 것
    @Test void abbreviatedNameWithAUnitMustMatchTheOwnedUnit() {
        var owned=new AnswerInput.Medication("199900003","가상약정5마이크로그램","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        extraction("LOOKUP","가상약 5mg");results(0,List.of());
        var other=new AnswerInput(1993,"UNKNOWN",false,"가상약 5mg 효능은?",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(other,ChatDeadline.after(Duration.ofSeconds(2))).medications()).isEmpty();
        verify(data,atLeastOnce()).get(anyString(),anyMap(),any(Duration.class));
        for (String said : List.of("가상약 5mcg","가상약 5μg","가상약 5")) {
            extraction("LOOKUP",said);
            var same=new AnswerInput(1993,"UNKNOWN",false,said+" 효능은?",List.of(owned),List.of(),false);
            assertThat(lookup.resolve(same,ChatDeadline.after(Duration.ofSeconds(2))).medications()).as(said).containsExactly(owned);
        }
    }
    // Codex 리뷰: 복합제 함량 차례를 보지 않아 '합성정5mg/10mg'가 '합성정10mg/5mg'로 바로 답하던 것
    @Test void abbreviatedCombinationStrengthsMustKeepTheirOrder() {
        var owned=new AnswerInput.Medication("199900031","합성정10mg/5mg","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        extraction("LOOKUP","합성정 5mg/10mg");results(0,List.of());
        var reversed=new AnswerInput(1993,"UNKNOWN",false,"합성정 5mg/10mg 효능은?",List.of(owned),List.of(),false);
        assertThat(lookup.resolve(reversed,ChatDeadline.after(Duration.ofSeconds(2))).medications()).isEmpty();
        for (String said : List.of("합성정 10mg/5mg","합성정 10/5","합성정 5")) {
            extraction("LOOKUP",said);
            var same=new AnswerInput(1993,"UNKNOWN",false,said+" 효능은?",List.of(owned),List.of(),false);
            assertThat(lookup.resolve(same,ChatDeadline.after(Duration.ofSeconds(2))).medications()).as(said).containsExactly(owned);
        }
    }
    // Codex 리뷰: 제형까지 말한 '가상약정10mg'가 서방정 내 약 '가상약정10mg서방정'으로 바로 답하던 것
    @Test void anExplicitFormDoesNotMatchAnOwnedReleaseVariant() {
        var owned=new AnswerInput.Medication("199900041","가상약정10mg서방정","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        for (String other : List.of("가상약정10mg","가상약 장용 10mg")) {
            extraction("LOOKUP",other);results(0,List.of());
            var input=new AnswerInput(1993,"UNKNOWN",false,other+" 효능은?",List.of(owned),List.of(),false);
            assertThat(lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2))).medications()).as(other).isEmpty();
        }
        for (String same : List.of("가상약 10mg","가상약정 10mg 서방정")) {
            extraction("LOOKUP",same);
            var input=new AnswerInput(1993,"UNKNOWN",false,same+" 효능은?",List.of(owned),List.of(),false);
            assertThat(lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2))).medications()).as(same).containsExactly(owned);
        }
    }
    // QA 2026-11-04: 등록은 '타이레놀 500'으로 후보를 찾는데 같은 이름으로 물으면 '찾지 못했어요'로 답함
    @Test void aBareNumberAfterTheNameIsReadAsMilligramsLikeRegistration() {
        extraction("LOOKUP","타이레놀 500");
        when(data.get(anyString(),anyMap(),any(Duration.class))).thenAnswer(call -> {
            var params=(Map<?,?>) call.getArgument(1);
            var items="타이레놀".equals(params.get("item_name"))
                    ? List.of(row("199900021","타이레놀정500밀리그람"),row("199900022","타이레놀산500밀리그램"),row("199900023","타이레놀8시간이알서방정650밀리그람"))
                    : List.<Map<String,Object>>of();
            return json.valueToTree(Map.of("totalCount",items.size(),"items",items));
        });
        var result=lookup.resolve(input("타이레놀 500 하루 2번 먹어도 돼?",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.reply()).startsWith(ChatDrugLookup.CHOICES).contains("타이레놀정500밀리그람","타이레놀산500밀리그램")
                .doesNotContain("650");
    }
    @Test void abbreviatedNameMatchingTwoOwnedProductsAsksAmongOwnedOnly() {
        extraction("LOOKUP","가상약");
        var ten=new AnswerInput.Medication("199900001","가상약정10mg","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        var twenty=new AnswerInput.Medication("199900002","가상약정20mg","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        var input=new AnswerInput(1993,"UNKNOWN",false,"가상약 효능은?",List.of(ten,twenty),List.of(),false);
        var result=lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.reply()).startsWith(ChatDrugLookup.CHOICES).contains("1. 가상약정10mg · 시험제약 · 내 약","2. 가상약정20mg");
        assertThat(result.sources()).extracting(s->s.itemSeq()).containsExactly("199900001","199900002");
        verifyNoInteractions(data);
    }
    @Test void publicChoicesListOwnedProductsFirst() {
        var owned=new AnswerInput.Medication("199900002","가상약정20mg","시험제약","ETC",null,null,null,null,null,LocalDate.now());
        extraction("LOOKUP","가상약");results(2,List.of(row("199900001","가상약정10mg"),row("199900002","가상약정20mg")));
        var input=new AnswerInput(1993,"UNKNOWN",false,"가상약 검색해줘",List.of(owned),List.of(),false);
        var result=lookup.resolve(input,ChatDeadline.after(Duration.ofSeconds(2)));
        assertThat(result.reply()).contains("1. 가상약정20mg · 시험제약 · 내 약","2. 가상약정10mg · 시험제약").doesNotContain("10mg · 시험제약 · 내 약");
    }
    @Test void pickingANumberAnswersTheQuestionAskedBeforeTheChoices() {
        extraction("LOOKUP","가상약");results(2,List.of(row("199900001","가상약정10밀리그램"),row("199900002","가상약정20밀리그램")));
        var first=lookup.resolve(input("가상약 효능 알려줘",List.of()),ChatDeadline.after(Duration.ofSeconds(2)));
        results(1,List.of(row("199900002","가상약정20밀리그램")));
        var recent=List.of(new AnswerInput.Turn(1,"가상약 효능 알려줘",sources.append(first.reply(),first.sources()),null,null));
        var selected=lookup.resolve(input("2번이요",recent),ChatDeadline.after(Duration.ofSeconds(2)));
        // 번호 대신 고른 제품 이름을 붙인다('2번 …을 선택하셨어요'로만 답함, QA 2026-11-04 2차)
        assertThat(selected.continuationQuestion())
                .isEqualTo("가상약 효능 알려줘\n(후보에서 고른 제품: 가상약정20밀리그램. 위 질문에 이 제품으로 답해 주세요.)");
    }
}
