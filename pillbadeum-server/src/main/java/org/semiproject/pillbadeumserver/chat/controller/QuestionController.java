package org.semiproject.pillbadeumserver.chat.controller;

import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.chat.service.QuestionService;
import org.semiproject.pillbadeumserver.chat.dto.QuestionRequest;
import org.semiproject.pillbadeumserver.chat.dto.QuestionResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CHAT-004·007: JSON 글자 질문을 받아 답변 저장 또는 차단 결과를 일괄 200으로 반환한다. */
@RestController
@RequestMapping("/api/chat-sessions/{sessionId}/messages")
public class QuestionController {
    private final QuestionService questions;

    public QuestionController(QuestionService questions) {
        this.questions = questions;
    }

    /** sessionId·content만 전달하고, 저장까지 끝난 ANSWERED 또는 BLOCKED 응답을 반환한다. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QuestionResponse> submit(@PathVariable @Positive Long sessionId,
                                                  @RequestBody QuestionRequest request) {
        var response = questions.submit(sessionId, request.content());
        return ResponseEntity.ok(response);
    }

    /** CHAT-004: 본문 없이 기존 질문 ID를 받는다. 질문을 재저장하지 않고 같은 턴의 답변만 복구한다. */
    @PostMapping("/{messageId}/retry")
    public ResponseEntity<QuestionResponse> retry(@PathVariable @Positive Long sessionId,
                                                 @PathVariable @Positive Long messageId) {
        return ResponseEntity.ok(questions.retry(sessionId, messageId));
    }
}
