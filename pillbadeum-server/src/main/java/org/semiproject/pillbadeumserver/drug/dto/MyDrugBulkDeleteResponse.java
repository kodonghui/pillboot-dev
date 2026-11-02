package org.semiproject.pillbadeumserver.drug.dto;

/* 설명. 약목록 여러 개 삭제 결과. 성공하면 지운 수는 늘 고른 수와 같다. (V3-DRUG-003) */
public record MyDrugBulkDeleteResponse(int deletedCount) {
}
