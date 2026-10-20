package org.semiproject.pillbadeumserver.chat.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.chat.service.MessageHistoryService;
import org.semiproject.pillbadeumserver.chat.dto.MessageHistoryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CHAT-003: 기존 질문 POST와 같은 경로의 GET으로 읽기 전용 대화 이력을 제공한다. */
@RestController
@RequestMapping("/api/chat-sessions/{sessionId}/messages")
public class MessageHistoryController {
    private final MessageHistoryService history;

    public MessageHistoryController(MessageHistoryService history) { this.history = history; }

    /** 생략한 beforeTurn은 최신 페이지, limit은 기본 30턴이다. 검증 후 Service에 값만 전달한다. */
    @GetMapping
    public MessageHistoryResponse read(@PathVariable @Positive Long sessionId,
                                       @RequestParam(required = false) @Positive Integer beforeTurn,
                                       @RequestParam(defaultValue = "30") @Min(1) @Max(30) int limit) {
        return history.read(sessionId, beforeTurn, limit);
    }
}
