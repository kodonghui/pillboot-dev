package org.semiproject.pillbadeumserver.chat.controller;

import org.semiproject.pillbadeumserver.chat.service.AnswerFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** CHAT-004의 질문 저장 후 503에만 questionMessageId를 붙이며 다른 API의 공통 오류 계약은 유지한다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = QuestionController.class)
public class QuestionExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(QuestionExceptionHandler.class);

    @ExceptionHandler(AnswerFailedException.class)
    public ResponseEntity<AnswerError> handle(AnswerFailedException exception) {
        var error = exception.getErrorCode();
        log.warn("[답변 실패] code={}", error.getCode());
        return ResponseEntity.status(error.getStatus())
                .body(new AnswerError(error.getCode(), exception.getMessage(), exception.getQuestionMessageId()));
    }

    public record AnswerError(String code, String message, Long questionMessageId) {}
}
