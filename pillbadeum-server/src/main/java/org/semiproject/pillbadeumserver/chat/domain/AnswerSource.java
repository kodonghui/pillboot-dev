package org.semiproject.pillbadeumserver.chat.domain;

import java.time.LocalDate;

/** CHAT-007: 서버가 조회한 근거의 표시 정보이다. 공식 정보와 사용자 입력을 source로 구분한다. */
public record AnswerSource(String itemSeq, String drugName, Type source, LocalDate updatedAt,
                           boolean ok, boolean cancelled) {
    public enum Type { EASY_DRUG, PERMIT, DUR, USER_INPUT }
}
