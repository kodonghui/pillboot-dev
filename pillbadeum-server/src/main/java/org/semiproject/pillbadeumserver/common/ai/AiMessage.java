package org.semiproject.pillbadeumserver.common.ai;

/* 설명. OpenAI 에 보낼 대화 한 줄 (CHAT-006 대화 맥락 조립에 쓴다)
 *  - role    : "user" (사용자 질문) 또는 "assistant" (이전 AI 답변)
 *  - content : 내용
 *  AI 의 역할 · 규칙(시스템 프롬프트)은 여기에 넣지 않고 OpenAiClient 의 instructions 로 따로 보낸다.
 *  사용 예) List.of(AiMessage.user("타이레놀이랑 감기약 같이 먹어도 돼?"))
 * */
public record AiMessage(String role, String content) {

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage("assistant", content);
    }
}
