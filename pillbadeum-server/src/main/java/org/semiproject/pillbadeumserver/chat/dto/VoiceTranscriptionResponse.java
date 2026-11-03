package org.semiproject.pillbadeumserver.chat.dto;

/** V3-KAKAO-006: 받아쓴 글(앞뒤 공백을 뺀 1자 이상). 1,000자 상한은 다리가 묶음 글과 합쳐 확인한다. */
public record VoiceTranscriptionResponse(String text) {}
