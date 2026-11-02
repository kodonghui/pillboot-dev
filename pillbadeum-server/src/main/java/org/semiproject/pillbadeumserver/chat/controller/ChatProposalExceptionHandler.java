package org.semiproject.pillbadeumserver.chat.controller;

import org.semiproject.pillbadeumserver.chat.dto.ChatProposalResponse;
import org.semiproject.pillbadeumserver.chat.service.ChatProposalConflictException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** V3-CHAT-003~005의 409에만 지금 제안 상태(proposal)를 붙인다(openapi-v3 ChatProposalConflict). 다른 오류는 공통 모양 그대로다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ChatProposalController.class)
public class ChatProposalExceptionHandler {

    @ExceptionHandler(ChatProposalConflictException.class)
    public ResponseEntity<Conflict> handle(ChatProposalConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Conflict(exception.getCode().name(), exception.getMessage(), exception.getProposal()));
    }

    public record Conflict(String code, String message, ChatProposalResponse proposal) {}
}
