package org.semiproject.pillbadeumserver.image.domain;

/** 사진 순서의 관찰값과 공식 면을 구분하는 대조 근거임. 확률이나 가중치가 아님. */
public record PillMatchDetail(
    String field, String referenceField, Object observedValue, Object referenceValue, String result) {}
