package org.semiproject.pillbadeumserver.chat.controller;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.semiproject.pillbadeumserver.chat.service.AnswerFailedException;
import org.semiproject.pillbadeumserver.chat.service.QuestionService;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * V3-CHAT-008: 검사를 마친 질문을 다른 스레드에서 실행하며 SSE로 보낸다.
 * 진행 단계마다 stage, 끝에 result(기존 JSON 응답과 같은 본문) 또는 error(기존 오류 본문 + status) 하나를 보내고 닫는다.
 * 화면 연결이 끊겨도 처리는 끝까지 하고 답변을 저장한다(기존 JSON 질문과 같음). 화면은 상담 기록으로 확인한다.
 */
@Component
public class QuestionStreams {
    private static final Logger log = LoggerFactory.getLogger(QuestionStreams.class);
    // 서버 작업 예산 85초에 전달 여유를 더함. 화면은 90초가 지나면 연결이 끊긴 것으로 본다.
    static final long TIMEOUT_MS = 95_000;
    // 답을 정리하는 동안(최대 60초) 이벤트가 없어도 중간 프록시가 연결을 끊지 않게 주석 줄을 보낸다.
    static final Duration KEEP_ALIVE = Duration.ofSeconds(15);

    private final AsyncTaskExecutor executor;
    private final TaskScheduler scheduler;

    public QuestionStreams(@Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor, TaskScheduler scheduler) {
        this.executor = executor;
        this.scheduler = scheduler;
    }

    /** 작업을 넘기지 못하면 진행 표식을 반납하고 오류를 그대로 던진다(기존 500 응답). */
    public SseEmitter open(QuestionService.Turn turn) {
        var emitter = new SseEmitter(TIMEOUT_MS);
        // 작업 예산(85초)이 먼저 끝나므로 보통 오지 않는다. 오면 오류 본문을 쓰지 않고 연결만 닫는다.
        emitter.onTimeout(emitter::complete);
        ScheduledFuture<?> keepAlive = null;
        try {
            keepAlive = scheduler.scheduleAtFixedRate(() -> send(emitter, SseEmitter.event().comment("keep-alive")),
                    Instant.now().plus(KEEP_ALIVE), KEEP_ALIVE);
            var started = keepAlive;
            executor.execute(() -> run(turn, emitter, started));
        } catch (RuntimeException exception) {
            if (keepAlive != null) keepAlive.cancel(false);
            turn.abandon();
            throw exception;
        }
        return emitter;
    }

    private void run(QuestionService.Turn turn, SseEmitter emitter, ScheduledFuture<?> keepAlive) {
        try {
            var response = turn.run(stage -> send(emitter, SseEmitter.event().name("stage")
                    .data(Map.of("stage", stage.name()), MediaType.APPLICATION_JSON)));
            send(emitter, SseEmitter.event().name("result").data(response, MediaType.APPLICATION_JSON));
        } catch (RuntimeException exception) {
            send(emitter, SseEmitter.event().name("error").data(errorOf(exception), MediaType.APPLICATION_JSON));
        } finally {
            keepAlive.cancel(false);
            emitter.complete();
        }
    }

    /** 끊긴 연결에 보내는 실패는 버린다. 저장은 이미 끝났거나 계속 진행 중이다. */
    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ignored) {
            // 화면이 떠났거나 시간이 다 된 연결이다.
        }
    }

    /** JSON 질문이 같은 예외에서 내는 상태·코드·문구. 503 답변 실패에만 저장된 질문 ID가 있다. */
    static StreamError errorOf(RuntimeException exception) {
        if (exception instanceof BusinessException business) {
            var code = business.getErrorCode();
            Long questionMessageId = exception instanceof AnswerFailedException failed ? failed.getQuestionMessageId() : null;
            log.warn("[답변 스트림 실패] code={}", code.getCode());
            return new StreamError(code.getStatus().value(), code.getCode(), business.getMessage(), questionMessageId);
        }
        // 원인 메시지에는 내부 구조·건강정보가 섞일 수 있어 종류만 남긴다(COMMON-004).
        log.error("[답변 스트림 내부 오류] type={}", exception.getClass().getSimpleName());
        var code = ErrorCode.INTERNAL_ERROR;
        return new StreamError(code.getStatus().value(), code.getCode(),
                "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", null);
    }

    /** error 이벤트 본문. status는 같은 실패에 JSON 질문이 쓰는 HTTP 상태다. */
    public record StreamError(int status, String code, String message, Long questionMessageId) {}
}
