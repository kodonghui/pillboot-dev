package org.semiproject.pillbadeumserver.chat.dto;

import java.time.OffsetDateTime;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;

/** CHAT-004·007: 답변 저장까지 끝난 ANSWERED 또는 저장 없는 BLOCKED 응답이다. */
public record QuestionResponse(Result result, Question question, Answer answer, Notice notice) {
    public static QuestionResponse answered(Question question, Answer answer) {
        return new QuestionResponse(Result.ANSWERED, question, answer, null);
    }

    public static QuestionResponse blocked(QuestionCategory category) {
        return new QuestionResponse(Result.BLOCKED, null, null, new Notice(category, category.noticeMessage()));
    }

    public enum Result { ANSWERED, BLOCKED }
    public record Question(Long messageId, int turnNo) {}
    public record Answer(Long messageId, int turnNo, String content, DurNotice durNotice, OffsetDateTime createdAt) {}
    public record Notice(QuestionCategory category, String message) {}
}
