package org.semiproject.pillbadeumserver.chat.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import org.semiproject.pillbadeumserver.chat.dto.QuestionRequest;
import org.semiproject.pillbadeumserver.chat.service.QuestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * V3-CHAT-008: 질문 보내기·다시 보내기를 SSE로 받는다. 요청·처리·결과는 QuestionController와 같고 진행 단계만 더 보낸다.
 * 스트림을 열기 전 오류(401·400·403·404·409)는 기존과 같은 JSON 오류 응답이다. 카카오 중계는 기존 JSON 경로를 그대로 쓴다.
 */
@RestController
@RequestMapping("/api/chat-sessions/{sessionId}/messages")
public class QuestionStreamController {
    private final QuestionService questions;
    private final QuestionStreams streams;

    public QuestionStreamController(QuestionService questions, QuestionStreams streams) {
        this.questions = questions;
        this.streams = streams;
    }

    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter submit(@PathVariable @Positive Long sessionId, @RequestBody QuestionRequest request,
                             HttpServletResponse response) {
        var turn = questions.startSubmit(sessionId, request.content());
        noBuffering(response);
        return streams.open(turn);
    }

    @PostMapping("/{messageId}/retry/stream")
    public SseEmitter retry(@PathVariable @Positive Long sessionId, @PathVariable @Positive Long messageId,
                            HttpServletResponse response) {
        var turn = questions.startRetry(sessionId, messageId);
        noBuffering(response);
        return streams.open(turn);
    }

    /** 중간 프록시(nginx 등)가 이벤트를 모아 두지 않고 바로 넘기게 한다. */
    private static void noBuffering(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
    }
}
