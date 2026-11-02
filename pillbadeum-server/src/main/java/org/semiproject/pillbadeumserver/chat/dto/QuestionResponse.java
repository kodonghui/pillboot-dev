package org.semiproject.pillbadeumserver.chat.dto;

import java.time.OffsetDateTime;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;

/** CHAT-004·007: 답변 저장까지 끝난 ANSWERED 또는 저장 없는 BLOCKED 응답이다. V3-CHAT-002·007은 답변에 제안 카드를 붙인다. */
public record QuestionResponse(Result result, Question question, Answer answer, Notice notice) {
    public static QuestionResponse answered(Question question, Answer answer) {
        return new QuestionResponse(Result.ANSWERED, question, answer, null);
    }

    public static QuestionResponse blocked(QuestionCategory category) {
        return new QuestionResponse(Result.BLOCKED, null, null, new Notice(category, category.noticeMessage()));
    }

    public enum Result { ANSWERED, BLOCKED }
    public record Question(Long messageId, int turnNo) {}
    /** proposal: V3-CHAT-002·007 제안 카드. 약 질문 답변과 제안 없는 안내는 null이다. */
    public record Answer(Long messageId, int turnNo, String content, DurNotice durNotice, OffsetDateTime createdAt,
                         ChatProposalResponse proposal) {
        public Answer(Long messageId, int turnNo, String content, DurNotice durNotice, OffsetDateTime createdAt) {
            this(messageId, turnNo, content, durNotice, createdAt, null);
        }

        public Answer withProposal(ChatProposalResponse value) {
            return new Answer(messageId, turnNo, content, durNotice, createdAt, value);
        }
    }
    public record Notice(QuestionCategory category, String message) {}
}
