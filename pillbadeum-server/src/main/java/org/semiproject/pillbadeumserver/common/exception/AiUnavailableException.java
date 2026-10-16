package org.semiproject.pillbadeumserver.common.exception;

/* 설명. AI 기능을 쓸 수 없는 상태일 때 -> 503 Service Unavailable
 *  예) OPENAI_API_KEY 가 설정되지 않았거나 키가 틀린 경우
 *  키가 없어도 서버는 정상으로 뜨고, AI 기능만 이 예외로 막는다. */
public class AiUnavailableException extends BusinessException {

    public AiUnavailableException() {
        super(ErrorCode.AI_UNAVAILABLE, "AI 기능을 지금 사용할 수 없습니다.");
    }

    public AiUnavailableException(String detail) {
        super(ErrorCode.AI_UNAVAILABLE, detail);
    }
}
