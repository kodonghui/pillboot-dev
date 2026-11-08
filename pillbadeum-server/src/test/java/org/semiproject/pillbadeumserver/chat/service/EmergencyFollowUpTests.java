package org.semiproject.pillbadeumserver.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.chat.domain.QuestionCategory;

/** 2026-11-08 QA: 응급·자해로 막은 분류를 프로필마다 30분 기억(질문 글 없이). 막을 때마다 30분이 다시 시작함. */
class EmergencyFollowUpTests {
    private Instant now = Instant.parse("2026-11-08T09:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @Test
    void remembersOnlyEmergenciesForThirtyMinutesPerProfile() {
        var memory = new EmergencyFollowUp(clock);
        memory.mark(10L, QuestionCategory.OFF_TOPIC);
        memory.mark(10L, QuestionCategory.PASS);
        assertThat(memory.recent(10L)).isNull();
        memory.mark(10L, QuestionCategory.EMERGENCY);
        assertThat(memory.recent(10L)).isEqualTo(QuestionCategory.EMERGENCY);
        assertThat(memory.recent(11L)).isNull();
        now = now.plus(Duration.ofMinutes(30));
        assertThat(memory.recent(10L)).as("30분까지는 기억").isEqualTo(QuestionCategory.EMERGENCY);
        now = now.plusSeconds(1);
        assertThat(memory.recent(10L)).isNull();
    }

    @Test
    void selfHarmIsKeptWithinTheWindowAndEachBlockRestartsIt() {
        var memory = new EmergencyFollowUp(clock);
        memory.mark(10L, QuestionCategory.SELF_HARM);
        now = now.plus(Duration.ofMinutes(20));
        memory.mark(10L, QuestionCategory.EMERGENCY);
        assertThat(memory.recent(10L)).as("30분 안의 자해는 109 안내를 위해 유지").isEqualTo(QuestionCategory.SELF_HARM);
        now = now.plus(Duration.ofMinutes(25));
        assertThat(memory.recent(10L)).as("마지막으로 막은 때부터 30분").isEqualTo(QuestionCategory.SELF_HARM);
        now = now.plus(Duration.ofMinutes(31));
        memory.mark(10L, QuestionCategory.EMERGENCY);
        assertThat(memory.recent(10L)).as("지난 자해는 잊음").isEqualTo(QuestionCategory.EMERGENCY);
        memory.mark(null, QuestionCategory.SELF_HARM);
        assertThat(memory.recent(null)).isNull();
    }

    @Test
    void followUpWordsAreRecognisedButOrdinaryQuestionsAreLeftToTheModel() {
        for (String said : new String[]{"괜찮겠지?", "병원 가기 싫은데", "토할 것 같아", "물 많이 마시면 돼?", "먹은 건 30분 전이야",
                "119 안 부르면 안돼?", "농담이야", "상담 전화 번호 알려줘", "고마워", "알겠어", "그냥 알려줘"})
            assertThat(ChatQuestionPurpose.emergencyFollowUp(said)).as(said).isTrue();
        for (String said : new String[]{"씨잘은 언제 먹어?", "내 약 일정 보여줘", "타이레놀 등록해 줘"})
            assertThat(ChatQuestionPurpose.emergencyFollowUp(said)).as(said).isFalse();
    }
}
