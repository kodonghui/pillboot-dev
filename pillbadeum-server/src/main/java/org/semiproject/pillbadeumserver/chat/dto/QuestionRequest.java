package org.semiproject.pillbadeumserver.chat.dto;

/** CHAT-004: 회원 번호는 받지 않고 질문 글자만 전달한다. 길이 검사는 Service에서 소유권 확인 후 수행한다. */
public record QuestionRequest(String content) {}
