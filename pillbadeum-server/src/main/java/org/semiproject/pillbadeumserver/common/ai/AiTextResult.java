package org.semiproject.pillbadeumserver.common.ai;

/** Responses의 완성된 본문과 선택적 보조 응답 ID이다. 전체 사용량을 개별 메시지 토큰 수로 옮기지 않는다. */
public record AiTextResult(String text, String responseId) {}
