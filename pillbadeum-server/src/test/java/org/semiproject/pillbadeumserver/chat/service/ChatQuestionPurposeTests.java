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
    @ValueSource(strings = {"아버지가 약 한 통을 다 드셨어", "수면제 한 통 먹었어", "수면제를 너무 많이 먹었어", "약을 왕창 먹었어",
            "약을 과다복용했어", "약을 다 몽땅 삼켰어"})
    void anOverdoseOfMedicineIsRecognized(String question) {
        assertThat(ChatQuestionPurpose.overdoseTaken(question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"밥을 잔뜩 먹었어. 지금 약 먹어도 돼?", "물 한 병 먹었어. 약 먹어도 돼?", "약 먹고 밥을 너무 많이 먹었어",
            "오늘 약 다 먹었어", "약 한 통 샀어", "약을 너무 많이 먹었을 때 어떻게 해야 해?", "약을 너무 많이 먹었으면 어떻게 돼?",
            "약 한 통을 주문했어. 언제 먹어야 해?", "약 한 통을 구매했어. 복용법 알려줘", "약을 과다복용했으면 어떻게 돼?", "약 한 통을 주문했어. 밥은 먹었어. 지금 약 먹어도 돼?",
            "약을 너무 많이 먹었다는 게 아니라 먹어도 되는지 물어본 거야.", "약을 한 통 다 먹은 건 아니야. 어제 먹었어",
            "약 한 통을 한 달 동안 처방대로 나눠 먹었어. 계속 먹어도 돼?"})
    void foodAndOrdinaryWordsAreNotAnOverdose(String question) {
        assertThat(ChatQuestionPurpose.overdoseTaken(question)).isFalse();
    }

    // QA 2026-11-05: '깜빡했어'가 용법 문서를 부르지 않아 '자료에 없어'로 답했음.
    @ParameterizedTest
    @ValueSource(strings = {"노바스크정 아침에 깜빡했어. 지금 먹어도 돼?", "약 까먹었는데 어떡해?", "아침 약 빼먹었어"})
    void aMissedDoseAsksForTheUsageDocument(String question) {
        assertThat(OfficialDrugEvidence.wantsDocument("UD_DOC_DATA", question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"노바스크 먹어도 돼?", "같이 먹어도 돼?", "지금 먹어도 돼?", "실수로 두 알 먹었어",
            "아침에 두 알로 일정 잡아줘", "노바스크정5밀리그람 효능이 뭐야?", "두 약을 같이 먹으면 안 돼?"})
    void otherWordsAreNot(String question) {
        assertThat(ChatQuestionPurpose.extraDose(question)).isFalse();
    }
}
