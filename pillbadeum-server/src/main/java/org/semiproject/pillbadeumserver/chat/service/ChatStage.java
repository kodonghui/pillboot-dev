package org.semiproject.pillbadeumserver.chat.service;

/**
 * V3-CHAT-008: 답변을 만드는 동안 화면에 보낼 진행 단계. 서버가 실제로 들어선 단계만 보내며, 질문에 따라 건너뛰는 단계가 있다.
 * (기록 질문·약 없음·제품 고르기 안내는 약 찾기·자료 확인을 건너뜀)
 */
public enum ChatStage {
    /** 질문 분류와 이전 대화·등록 약 불러오기 */
    UNDERSTANDING,
    /** 질문 속 약을 등록 약·공공데이터에서 찾기 */
    FINDING_DRUG,
    /** 공식 자료(e약은요·허가정보)와 DUR 금기 확인 */
    CHECKING,
    /** AI가 답을 정리하기 */
    WRITING
}
