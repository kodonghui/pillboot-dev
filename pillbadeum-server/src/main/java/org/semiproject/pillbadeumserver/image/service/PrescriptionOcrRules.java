package org.semiproject.pillbadeumserver.image.service;

/** REG-003: 제품 식별에 필요한 함량·제형과 복용 단위를 구분하는 주/보조 경로 공통 지시임. */
final class PrescriptionOcrRules {
    private PrescriptionOcrRules() {}

    static final String PRODUCT_NAME = """
            제품명에 인쇄된 함량(예: 60mg·100밀리그램)은 제품 식별 정보이므로 보존한다.
            /1정·/1캡슐 같은 투여 단위와 복용 횟수·기간은 제품명에 포함하지 않는다.
            서방·장용·정·캡슐 등 제품 제형 문구는 삭제하거나 다른 말로 추측하지 않는다.
            함량과 제약사를 읽을 수 없으면 채우지 않으며 보이는 제품명만 반환한다.
            제품명은 인쇄된 글자와 줄 순서대로 읽으며 비슷한 다른 의약품 이름으로 보정하지 않는다.
            제약사는 사진에 회사명이 직접 인쇄된 경우만 읽고 제품명의 접두어나 의약품 지식에서 추정하지 않는다.
            mg·밀리그램 등 단위는 보이는 표기를 유지하며 임의 환산하지 않는다.
            """;
}
