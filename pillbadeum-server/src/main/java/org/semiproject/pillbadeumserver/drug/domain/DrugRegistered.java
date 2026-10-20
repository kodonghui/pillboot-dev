package org.semiproject.pillbadeumserver.drug.domain;

import java.util.List;

/** COMMON-005: 저장된 공식 품목코드만 전달한다. 프로필·사용자·복용정보·직접 입력 이름은 넣지 않는다. */
public record DrugRegistered(List<String> itemSeqs) {
    public DrugRegistered { itemSeqs = List.copyOf(itemSeqs); }
}
