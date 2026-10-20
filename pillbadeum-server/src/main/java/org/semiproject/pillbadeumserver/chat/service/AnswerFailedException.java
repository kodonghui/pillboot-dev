package org.semiproject.pillbadeumserver.chat.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;

/** CHAT-004: 질문 커밋 후의 실패를 질문 ID와 함께 전달한다. 질문 삭제·재저장을 요청하지 않는다. */
public class AnswerFailedException extends BusinessException {
    private final Long questionMessageId;

    public AnswerFailedException(Long questionMessageId, RuntimeException cause) {
        super(cause instanceof BusinessException business && business.getErrorCode() == ErrorCode.EXTERNAL_API_TIMEOUT
                ? ErrorCode.EXTERNAL_API_TIMEOUT : ErrorCode.EXTERNAL_API_FAILED,
                "답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
        this.questionMessageId = questionMessageId;
    }

    public Long getQuestionMessageId() { return questionMessageId; }
}
