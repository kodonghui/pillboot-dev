package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** CHAT-006·007: 짧은 읽기 트랜잭션 밖에서도 사용할 최소 사본이다. 이름·회원 정보는 포함하지 않는다. */
public record AnswerInput(long birthYear, String gender, Boolean pregnant, String question,
                          List<Medication> medications, List<Turn> recentTurns, boolean firstAnswer) {
    public record Medication(String itemSeq, String name, String maker, String etcOtc, int dosePerTime,
                              String timing, LocalDate startDate, LocalDate endDate,
                              List<LocalTime> doseTimes, LocalDate updatedAt) {}
    public record Turn(int turnNo, String question, String answer, Integer questionTokens, Integer answerTokens) {}
}
