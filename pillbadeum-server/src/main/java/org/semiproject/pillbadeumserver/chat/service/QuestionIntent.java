package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;

/**
 * V3-CHAT-001: 안전 분류를 통과한 질문의 의도(AI 호출 8.1). 요청 안에서만 쓰고 DB·공개 응답에 남기지 않는다.
 * @param drugMentions 등록 요청에서 뽑은 이름·함량 원문(질문에 적힌 글자, 최대 10). 다른 의도면 빈 목록
 * @param targetMyDrugName 일정 요청의 대상 약(약목록 이름 중 하나). 하나로 정하지 못했으면 null
 */
public record QuestionIntent(Kind kind, List<String> drugMentions, String targetMyDrugName) {
    public enum Kind { QUESTION, REGISTER_DRUG, SCHEDULE_CHANGE }

    public QuestionIntent {
        drugMentions = drugMentions == null ? List.of() : List.copyOf(drugMentions);
    }

    /** 판단이 애매하거나 AI가 실패했을 때의 기본값. 기존 답변 흐름을 탄다. */
    public static QuestionIntent question() {
        return new QuestionIntent(Kind.QUESTION, List.of(), null);
    }
}
