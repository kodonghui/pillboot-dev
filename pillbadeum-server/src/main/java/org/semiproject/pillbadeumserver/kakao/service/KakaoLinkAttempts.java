package org.semiproject.pillbadeumserver.kakao.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * V3-REQ-90: 카톡에서 보낸 연결 코드의 틀린 횟수와 서버 전체 확인 횟수. 서버 메모리에만 둔다(다시 켜면 처음부터 셈).
 * - 카카오 사용자(HMAC)마다 틀린 코드 5번이면 그때부터 1시간 동안 맞는 코드도 받지 않는다. 맞으면 센 값을 지운다.
 * - 서버 전체 코드 확인은 1분에 60번까지. 넘은 요청은 틀린 횟수에 세지 않는다.
 */
@Component
public class KakaoLinkAttempts {

    static final int MAX_WRONG = 5;
    static final Duration LOCK = Duration.ofHours(1);
    static final int MAX_CHECKS_PER_MINUTE = 60;
    private static final int MAX_TRACKED_USERS = 10_000;

    private final Map<String, Wrong> wrong = new HashMap<>();
    private final Deque<Instant> checks = new ArrayDeque<>();
    private Clock clock = Clock.systemUTC();

    private static final class Wrong {
        int count;
        Instant lockedUntil;
        Instant lastAt;
    }

    public enum Admission { OK, LOCKED, BUSY }

    /** 코드를 확인하기 전에 부른다. OK이면 서버 전체 확인 횟수에 하나를 더한다. */
    public synchronized Admission admit(String user) {
        Instant now = clock.instant();
        Wrong state = wrong.get(user);
        if (state != null && state.lockedUntil != null) {
            if (now.isBefore(state.lockedUntil)) {
                return Admission.LOCKED;
            }
            wrong.remove(user);
        }
        while (!checks.isEmpty() && !checks.peekFirst().isAfter(now.minusSeconds(60))) {
            checks.removeFirst();
        }
        if (checks.size() >= MAX_CHECKS_PER_MINUTE) {
            return Admission.BUSY;
        }
        checks.addLast(now);
        return Admission.OK;
    }

    public synchronized void wrong(String user) {
        Instant now = clock.instant();
        if (wrong.size() >= MAX_TRACKED_USERS && !wrong.containsKey(user)) {
            forgetStale(now);
        }
        Wrong state = wrong.computeIfAbsent(user, key -> new Wrong());
        state.count++;
        state.lastAt = now;
        if (state.count >= MAX_WRONG) {
            state.lockedUntil = now.plus(LOCK);
        }
    }

    public synchronized void succeeded(String user) {
        wrong.remove(user);
    }

    /** 1시간 넘게 틀리지 않았고 막히지 않은 사람은 잊는다. */
    private void forgetStale(Instant now) {
        Iterator<Wrong> values = wrong.values().iterator();
        while (values.hasNext()) {
            Wrong state = values.next();
            boolean locked = state.lockedUntil != null && now.isBefore(state.lockedUntil);
            if (!locked && state.lastAt.isBefore(now.minus(LOCK))) {
                values.remove();
            }
        }
    }

    /** 시험에서 시각을 옮길 때만 쓴다. */
    synchronized void useClock(Clock clock) {
        this.clock = clock;
    }

    synchronized void clear() {
        wrong.clear();
        checks.clear();
    }
}
