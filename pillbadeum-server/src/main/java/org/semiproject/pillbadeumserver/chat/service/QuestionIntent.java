package org.semiproject.pillbadeumserver.chat.service;

import java.util.List;

/**
 * V3-CHAT-001: 안전 분류를 통과한 질문의 의도(AI 호출 8.1). 요청 안에서만 쓰고 DB·공개 응답에 남기지 않는다.
 * @param drugMentions 등록 요청에서 뽑은 이름·함량 원문(질문이나 바로 앞 회원 말에 적힌 글자, 최대 10). 다른 의도면 빈 목록
 * @param targetMyDrugNames 일정 요청의 대상 약(약목록 이름, 최대 10). 정하지 못했으면 빈 목록
 * @param allMyDrugs 2026-11-08: 일정 요청이 약목록의 모든 약을 가리킴('약목록 전부', '나 먹는 약들')
 * @param request 2026-11-08: 앞 대화에서 회원이 이미 말한 값을 모아 다시 쓴 한 문장 요청. 숫자가 회원 말에 없거나
 *                쓰지 않았으면 null(질문 글 그대로 씀)
 */
public record QuestionIntent(Kind kind, List<String> drugMentions, List<String> targetMyDrugNames, boolean allMyDrugs,
                             String request) {
    public enum Kind { QUESTION, REGISTER_DRUG, SCHEDULE_CHANGE }

    public QuestionIntent {
        drugMentions = drugMentions == null ? List.of() : List.copyOf(drugMentions);
        targetMyDrugNames = targetMyDrugNames == null ? List.of() : List.copyOf(targetMyDrugNames);
        request = request == null || request.isBlank() ? null : request.strip();
    }

    public QuestionIntent(Kind kind, List<String> drugMentions, String targetMyDrugName) {
        this(kind, drugMentions, targetMyDrugName == null ? List.of() : List.of(targetMyDrugName), false, null);
    }

    /** 대상 약이 하나로 정해졌으면 그 이름, 아니면 null(기존 일정 제안 한 약 흐름). */
    public String targetMyDrugName() {
        return targetMyDrugNames.size() == 1 && !allMyDrugs ? targetMyDrugNames.getFirst() : null;
    }

    /** 판단이 애매하거나 AI가 실패했을 때의 기본값. 기존 답변 흐름을 탄다. */
    public static QuestionIntent question() {
        return new QuestionIntent(Kind.QUESTION, List.of(), null);
    }
}
