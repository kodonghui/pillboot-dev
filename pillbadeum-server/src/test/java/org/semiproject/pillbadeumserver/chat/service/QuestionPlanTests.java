package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;
import static org.assertj.core.api.Assertions.assertThat;

class QuestionPlanTests {
    @ParameterizedTest
    @ValueSource(strings = {"임산부는 먹으면 안되는 약이 있어?", "수유 중에도 괜찮아?", "내 저녁 약 두 배는?",
            "식전에는 먹으면 안되는거야?", "같이 먹어도 돼?"})
    void medicalQuestionCannotUseRecordsAloneEvenWhenTheModelSaysRecord(String question) {
        var plan = QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, question, List.of());
        assertThat(plan.purpose()).isEqualTo(QuestionPlan.Purpose.COMBINED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"엄마가 먹는 타이레놀 보관은 어떻게 해?",
            "엄마 약 목록에 있는 타이레놀정500밀리그람(아세트아미노펜)의 보관방법을 알려줘",
            "내 저녁 약은 냉장 보관해야 해?", "내 약 목록에 있는 약의 보관방법은?",
            "등록한 약의 보관 방법을 짧게 알려줘", "내 약 보관 조건을 표로 정리해줘"})
    void storageQuestionsRequireOfficialEvidenceEvenWhenTheyMentionRegisteredRecords(String question) {
        assertThat(ChatQuestionPurpose.medicalQuestion(question)).isTrue();
        assertThat(ChatQuestionPurpose.recordOnly(question)).isFalse();
        var plan = QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, question, List.of());
        assertThat(plan.purpose()).isEqualTo(QuestionPlan.Purpose.COMBINED);
        assertThat(plan.evidenceQuestion()).isEqualTo(question);
    }

    @Test
    void coadministrationInsideAListRequestStillRequiresOfficialEvidenceAndDur() {
        String question = "내 약 목록 중 같이 먹을 수 있는 약을 정리해줘";
        assertThat(ChatQuestionPurpose.coadministration(question)).isTrue();
        assertThat(ChatQuestionPurpose.recordOnly(question)).isFalse();
        assertThat(QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.RECORD, question, List.of()).purpose())
                .isEqualTo(QuestionPlan.Purpose.COMBINED);
        assertThat(ChatQuestionPurpose.recordOnly("내 약 목록을 함께 표로 정리해줘")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"권장량 말고 등록한 개수와 시각만 알려줘", "검색은 그만하고 내 저녁 약 이름과 시각만",
            "내 약에 입력해 둔 식전·식후 기록만 읽어줘. 권장 방법을 묻는 건 아니야."})
    void clearNewRecordQuestionDoesNotInheritThePreviousMedicalTopic(String question) {
        var plan = QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.COMBINED, question, List.of("임산부 주의사항은?"));
        assertThat(plan.purpose()).isEqualTo(QuestionPlan.Purpose.RECORD);
        assertThat(plan.evidenceQuestion()).isEqualTo(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"내 약 목록만 알려줘", "내 원래 저녁 약 이름과 시각만 알려줘",
            "내 약 일정이 저장된 시간을 알려줘", "내 약 일정 저장 방법을 알려줘", "내가 아까 물어본 약 이름은?"})
    void returningToRecordsAfterStorageAdviceDoesNotRepeatOfficialLookups(String question) {
        var plan = QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.COMBINED,
                question, List.of("등록한 약의 보관방법은?"));
        assertThat(plan.purpose()).isEqualTo(QuestionPlan.Purpose.RECORD);
        assertThat(plan.evidenceQuestion()).isEqualTo(question);
    }

    @Test
    void shortFollowUpRetainsMedicalTopicButBlockingIsNeverOverridden() {
        var plan = QuestionPlan.guarded(QuestionCategory.PASS, QuestionPlan.Purpose.OFFICIAL, "그럼 두 번째는?", List.of("임산부는 먹으면 안되는 약이 있어?"));
        assertThat(plan.evidenceQuestion()).contains("임산부", "두 번째");
        assertThat(QuestionPlan.guarded(QuestionCategory.UNSAFE, QuestionPlan.Purpose.COMBINED,
                "내 약 이름과 시각", List.of()).category()).isEqualTo(QuestionCategory.UNSAFE);
    }

    @Test
    void pregnancyTopicLoadsCautionsAndPregnancyDurEvenForAMaleProfileWithoutMutatingIt() {
        var drug = new AnswerInput.Medication("197400040", "합성약", "", "ETC", null, "", null, null, List.of(), LocalDate.now());
        var input = new AnswerInput(1993, "MALE", false, "임산부는 먹으면 안되는 약이 있어?", List.of(drug), List.of(), false);
        assertThat(DurLookupService.requiredTypes(input, 1, LocalDate.now())).contains(DurLookup.Type.PREGNANCY);
        assertThat(OfficialDrugEvidence.wantsDocument("NB_DOC_DATA", input.question())).isTrue();
        var pregnancy = new DurLookup(drug.itemSeq(), DurLookup.Type.PREGNANCY, true,
                List.of(new DurLookup.Row(null, "합성성분", "", "합성 임부 주의", LocalDate.now())), LocalDate.now());
        var evaluation = new DurEvaluator().evaluate(input, List.of(pregnancy));
        assertThat(evaluation.notice().items()).anySatisfy(item -> assertThat(item.content()).contains("임신 중이라면", "합성 임부 주의"));
        assertThat(input.gender()).isEqualTo("MALE");
        assertThat(input.pregnant()).isFalse();
    }

    @Test
    void overlappingIngredientQuestionLoadsTheActualCautionDocument() {
        assertThat(OfficialDrugEvidence.wantsDocument("NB_DOC_DATA", "다른 아세트아미노펜 제품과 겹칠 수 있어?")).isTrue();
    }
}
