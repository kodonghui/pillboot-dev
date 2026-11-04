package org.semiproject.pillbadeumserver.kakao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.kakao.service.KakaoLinkAttempts.Admission;

/** V3-REQ-90: 틀린 코드 5번이면 1시간 막고, 서버 전체 확인은 1분에 60번까지. */
class KakaoLinkAttemptsTests {

    private final KakaoLinkAttempts attempts = new KakaoLinkAttempts();
    private Instant now = Instant.parse("2026-11-04T01:00:00Z");
    private final String user = "a".repeat(64);
    private final String other = "b".repeat(64);

    @BeforeEach
    void useTestClock() {
        attempts.useClock(new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        });
    }

    @Test
    void fiveWrongCodesLockOnlyThatUserForOneHour() {
        for (int i = 0; i < 5; i++) {
            assertThat(attempts.admit(user)).isEqualTo(Admission.OK);
            attempts.wrong(user);
        }
        assertThat(attempts.admit(user)).isEqualTo(Admission.LOCKED);
        assertThat(attempts.admit(other)).isEqualTo(Admission.OK);
        now = now.plus(Duration.ofMinutes(59));
        assertThat(attempts.admit(user)).isEqualTo(Admission.LOCKED);
        now = now.plus(Duration.ofMinutes(1));
        assertThat(attempts.admit(user)).as("The lock ends after one hour and counting starts over").isEqualTo(Admission.OK);
        attempts.wrong(user);
        assertThat(attempts.admit(user)).isEqualTo(Admission.OK);
    }

    @Test
    void aCorrectCodeForgetsEarlierWrongOnes() {
        for (int i = 0; i < 4; i++) {
            attempts.admit(user);
            attempts.wrong(user);
        }
        attempts.succeeded(user);
        attempts.admit(user);
        attempts.wrong(user);
        assertThat(attempts.admit(user)).isEqualTo(Admission.OK);
    }

    @Test
    void theWholeServerChecksAtMostSixtyCodesAMinuteAndBusyIsNotCountedAsWrong() {
        for (int i = 0; i < 60; i++) {
            assertThat(attempts.admit(i % 2 == 0 ? user : other)).isEqualTo(Admission.OK);
        }
        assertThat(attempts.admit(user)).isEqualTo(Admission.BUSY);
        now = now.plusSeconds(60);
        assertThat(attempts.admit(user)).isEqualTo(Admission.OK);
    }
}
