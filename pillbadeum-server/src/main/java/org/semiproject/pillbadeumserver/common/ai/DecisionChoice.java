package org.semiproject.pillbadeumserver.common.ai;

/** Decisions API의 고정 선택지. value는 코드에서 검사할 값, description은 모델에 전달할 판단 기준이다. */
public record DecisionChoice(String value, String description) {
}
