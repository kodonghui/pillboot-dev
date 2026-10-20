package org.semiproject.pillbadeumserver.chat.service;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.semiproject.pillbadeumserver.common.exception.ExternalApiException;

/** CHAT-004: 외부 작업이 공유하는 종료 시점이다. 시간 초과 후 완료된 작업에서 DB를 쓰지 않게 한다. */
public final class ChatDeadline {
    private final long endsAt;

    private ChatDeadline(long endsAt) { this.endsAt = endsAt; }

    public static ChatDeadline after(Duration budget) {
        return new ChatDeadline(System.nanoTime() + budget.toNanos());
    }

    public ChatDeadline within(Duration limit) {
        return new ChatDeadline(Math.min(endsAt, System.nanoTime() + limit.toNanos()));
    }

    public void check() {
        if (Thread.currentThread().isInterrupted() || endsAt - System.nanoTime() <= 0) {
            throw ExternalApiException.timeout("챗봇", null);
        }
    }

    /** 공통 외부 클라이언트의 permit 대기도 같은 남은 예산 안에서 수행함. */
    public Duration remaining() {
        check();
        return Duration.ofNanos(Math.max(1, endsAt - System.nanoTime()));
    }

    /** Future는 DB 쓰기가 없는 작업만 받는다. 호출자는 결과를 받은 뒤 짧은 저장 트랜잭션을 시작한다. */
    public <T> T await(Future<T> task) {
        try {
            check();
            return task.get(Math.max(1, endsAt - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw ExternalApiException.timeout("챗봇", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ExternalApiException.timeout("챗봇", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw ExternalApiException.failed("챗봇", "답변 작업을 처리할 수 없습니다.", exception);
        } finally {
            if (!task.isDone()) task.cancel(true);
        }
    }
}
