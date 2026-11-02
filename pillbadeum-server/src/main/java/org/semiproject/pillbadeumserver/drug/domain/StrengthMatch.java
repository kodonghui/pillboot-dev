package org.semiproject.pillbadeumserver.drug.domain;

/** V3-REG-001·002: 검색·판독 이름의 함량과 후보 함량을 비교한 결과. */
public enum StrengthMatch {
    MATCH,      // 질량 함량이 같거나, 복합·농도 함량의 원문 글자가 같음
    DIFFERENT,  // 둘 다 함량이 있지만 다름 ('함량 다름')
    UNKNOWN     // 검색어나 후보에 함량이 없어 비교할 수 없음
}
