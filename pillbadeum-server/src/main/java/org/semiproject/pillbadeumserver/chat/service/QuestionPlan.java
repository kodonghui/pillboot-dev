package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;

/**
 * 요청 안에서만 전달하는 조회 목적이다. 공개 응답·DB·장기 캐시에 저장하지 않는다.
 * afterEmergency: 2026-11-08 같은 프로필에서 30분 안에 응급·자해로 막은 뒤의 이어 말이라 같은 분류로 막음(이어 말 안내를 씀).
 */
public record QuestionPlan(QuestionCategory category, Purpose purpose, String evidenceQuestion, boolean afterEmergency) {
    public enum Purpose { RECORD, OFFICIAL, COMBINED }

    public QuestionPlan(QuestionCategory category, Purpose purpose, String evidenceQuestion) {
        this(category, purpose, evidenceQuestion, false);
    }

    static QuestionPlan guarded(QuestionCategory category, Purpose purpose, String question, List<String> previous) {
        if (!category.isPassed()) return new QuestionPlan(category, purpose, question);
        if (ChatQuestionPurpose.recordOnly(question)) purpose = Purpose.RECORD;
        else if (ChatQuestionPurpose.medicalQuestion(question) && purpose == Purpose.RECORD) purpose = Purpose.COMBINED;
        String scope = question;
        if (purpose != Purpose.RECORD && !ChatQuestionPurpose.medicalQuestion(question))
            scope = String.join(" ", previous) + " " + question;
        return new QuestionPlan(category, purpose, scope.strip());
    }

    static QuestionPlan legacy(AnswerInput input) {
        return guarded(QuestionCategory.PASS, Purpose.COMBINED, input.question(), List.of());
    }
}
