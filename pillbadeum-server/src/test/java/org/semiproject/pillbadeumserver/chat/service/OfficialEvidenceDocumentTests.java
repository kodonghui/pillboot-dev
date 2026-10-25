package org.semiproject.pillbadeumserver.chat.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;
import org.semiproject.pillbadeumserver.drug.service.OfficialDrugDocuments;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OfficialEvidenceDocumentTests {
    private final PublicDataClient data=mock(PublicDataClient.class);
    private final OfficialDrugDocuments documents=mock(OfficialDrugDocuments.class);
    private final JsonMapper json=JsonMapper.builder().build();
    private SimpleAsyncTaskExecutor executor;
    private OfficialDrugEvidence evidence;
    private final AnswerInput.Medication drug=new AnswerInput.Medication("199900001","가상약","시험제약","ETC",null,null,null,null,null,LocalDate.now());
    @BeforeEach void setup() {
        executor=new SimpleAsyncTaskExecutor();executor.setVirtualThreads(true);
        evidence=new OfficialDrugEvidence(data,executor,4,documents);
    }
    @AfterEach void close() {executor.close();}
    private void provider(Map<String,Object> fields) {
        var item=new HashMap<String,Object>(fields);item.put("ITEM_SEQ",drug.itemSeq());
        when(data.get(anyString(),anyMap(),any(Duration.class))).thenAnswer(call->json.valueToTree(call.<String>getArgument(0).equals(OfficialDrugEvidence.EASY_PATH)
                ? Map.of("totalCount",0,"items",List.of()) : Map.of("totalCount",1,"items",List.of(item))));
    }
    @Test void missingInlineEfficacyReadsSameProductOfficialDocumentAndKeepsFullText() {
        provider(Map.of("EE_DOC_ID","official","UD_DOC_ID","official","NB_DOC_ID","official","STORAGE_METHOD","storage"));
        when(documents.read(eq(drug.itemSeq()),eq("EE"),any(Duration.class))).thenReturn("Official efficacy < 5 mg; age > 12. Preserve entire medical sentence.");
        var result=evidence.collect(List.of(drug),ChatDeadline.after(Duration.ofSeconds(2)),"효능은?");
        assertThat(result.lookups().get(1).facts().get("EE_DOC_DATA")).isEqualTo("Official efficacy < 5 mg; age > 12. Preserve entire medical sentence.");
        assertThat(result.hasFailures()).isFalse();verify(documents).read(eq(drug.itemSeq()),eq("EE"),any(Duration.class));
        verifyNoMoreInteractions(documents);
    }
    @Test void originalInlineMedicalTextWinsOverPdf() {
        provider(Map.of("EE_DOC_DATA","<p>original efficacy</p>","EE_DOC_ID","official"));
        var result=evidence.collect(List.of(drug),ChatDeadline.after(Duration.ofSeconds(2)),"효능 알려줘");
        assertThat(result.lookups().get(1).facts().get("EE_DOC_DATA")).isEqualTo("original efficacy");verifyNoInteractions(documents);
    }
    @Test void documentFailureRetainsOtherFactsAndIsExplicitlyPartial() {
        provider(Map.of("NB_DOC_ID","official","STORAGE_METHOD","storage"));
        when(documents.read(eq(drug.itemSeq()),eq("NB"),any(Duration.class))).thenThrow(ExternalApiException.timeout("official",null));
        var result=evidence.collect(List.of(drug),ChatDeadline.after(Duration.ofSeconds(2)),"천식 주의는?");
        assertThat(result.hasFailures()).isTrue();assertThat(result.lookups().get(1).facts()).containsEntry("STORAGE_METHOD","storage");
    }
    @Test void ordinaryScheduleQuestionDoesNotDownloadMedicalPdfs() {
        provider(Map.of("EE_DOC_ID","official","NB_DOC_ID","official","STORAGE_METHOD","storage"));
        evidence.collect(List.of(drug),ChatDeadline.after(Duration.ofSeconds(2)),"아침 등록 시각만 알려줘");verifyNoInteractions(documents);
    }
}
