package org.semiproject.pillbadeumserver.drug.domain;

/*  내 약을 어떤 방법으로 등록했는지 (my_drug.source)
 *  ----------------------------------------------------------------------------------
 *  '약 등록 방법 고르기' 화면의 선택값과 1:1 로 대응한다. 프론트 · 이미지 담당과 같은 값을 쓴다.
 *  - SEARCH             : 약 이름 검색으로 찾은 약
 *  - PILL_PHOTO         : 알약 사진(앞 · 뒤)으로 식별한 약
 *  - PRESCRIPTION_PHOTO : 처방전 · 약봉투 사진에서 읽은 약
 *  ----------------------------------------------------------------------------------
 *  DB 에는 이름 그대로 문자열로 저장한다. (MyDrug 에서 @Enumerated(EnumType.STRING))
 *  my_drug.source 는 VARCHAR(30) 이므로 새 값을 추가할 때 30자를 넘기지 않는다.
 *  상수 순서(ordinal)는 저장에 쓰지 않으므로 순서를 바꿔도 데이터에는 영향이 없다.
 * */
public enum DrugSource {

    SEARCH("이름 검색"),
    PILL_PHOTO("알약 사진"),
    PILL_CLUES("알약 정보 입력"),
    PRESCRIPTION_PHOTO("처방전 사진"),
    MANUAL("직접 입력");

    private final String description;   // 화면 · 응답에 보여줄 한글 이름

    DrugSource(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
