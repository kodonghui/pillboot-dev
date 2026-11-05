package org.semiproject.pillbadeumserver.chat.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** QA 2026-11-05: 늘려 먹으려는 말을 가르는 경계. 이미 먹은 일과 등록 일정은 말리는 문장 대상이 아니다. */
class ChatQuestionPurposeTests {
    @ParameterizedTest
    @ValueSource(strings = {"그럼 내일 두 알 먹으면 되지?", "한 번에 2정 복용해도 돼?", "저녁에 몰아서 먹어도 될까?",
            "두 배로 먹어도 돼?", "한꺼번에 먹을까?", "약을 더 먹어도 돼?", "아버지께 세 개 드려도 돼?"})
    void takingMoreIsRecognized(String question) {
        assertThat(ChatQuestionPurpose.extraDose(question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"노바스크 먹어도 돼?", "같이 먹어도 돼?", "지금 먹어도 돼?", "실수로 두 알 먹었어",
            "아침에 두 알로 일정 잡아줘", "노바스크정5밀리그람 효능이 뭐야?", "두 약을 같이 먹으면 안 돼?"})
    void otherWordsAreNot(String question) {
        assertThat(ChatQuestionPurpose.extraDose(question)).isFalse();
    }
}
