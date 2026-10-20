package org.semiproject.pillbadeumserver.chat.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** CHAT-006·007: 짧은 읽기 트랜잭션 밖에서도 사용할 최소 사본이다. 이름·회원 정보는 포함하지 않는다. */
public record AnswerInput(long birthYear, String gender, Boolean pregnant, String question,
                          List<Medication> medications, List<Turn> recentTurns, boolean firstAnswer,
                          List<Summary> recentSummaries, List<Summary> similarSummaries, long summaryCount,
                          List<String> unidentifiedDrugNames) {
    public AnswerInput {
        unidentifiedDrugNames = List.copyOf(unidentifiedDrugNames);
    }
    /** 기존 맥락 입력은 품목 미확인 약이 없는 경우로 유지한다. */
    public AnswerInput(long birthYear, String gender, Boolean pregnant, String question,
                       List<Medication> medications, List<Turn> recentTurns, boolean firstAnswer,
                       List<Summary> recentSummaries, List<Summary> similarSummaries, long summaryCount) {
        this(birthYear, gender, pregnant, question, medications, recentTurns, firstAnswer,
                recentSummaries, similarSummaries, summaryCount, List.of());
    }
    /** 요약이 없는 기존 경로·짧은 대화도 같은 입력 계약으로 처리한다. */
    public AnswerInput(long birthYear, String gender, Boolean pregnant, String question,
                       List<Medication> medications, List<Turn> recentTurns, boolean firstAnswer) {
        this(birthYear, gender, pregnant, question, medications, recentTurns, firstAnswer, List.of(), List.of(), 0);
    }
    public AnswerInput(long birthYear, String gender, Boolean pregnant, String question,
                       List<Medication> medications, List<Turn> recentTurns, boolean firstAnswer, List<Summary> recentSummaries) {
        this(birthYear, gender, pregnant, question, medications, recentTurns, firstAnswer, recentSummaries, List.of(), recentSummaries.size());
    }

    public AnswerInput withSimilarSummaries(List<Summary> summaries) {
        return new AnswerInput(birthYear, gender, pregnant, question, medications, recentTurns, firstAnswer,
                recentSummaries, summaries, summaryCount, unidentifiedDrugNames);
    }
    public AnswerInput withUnidentifiedDrugNames(List<String> names) {
        return new AnswerInput(birthYear, gender, pregnant, question, medications, recentTurns, firstAnswer,
                recentSummaries, similarSummaries, summaryCount, names);
    }
    public record Medication(String itemSeq, String name, String maker, String etcOtc, Integer dosePerTime,
                              String timing, LocalDate startDate, LocalDate endDate,
                              List<LocalTime> doseTimes, LocalDate updatedAt) {}
    public record Turn(int turnNo, String question, String answer, Integer questionTokens, Integer answerTokens) {}
    public record Summary(long summaryId, int fromTurn, int toTurn, String content) {}
}
