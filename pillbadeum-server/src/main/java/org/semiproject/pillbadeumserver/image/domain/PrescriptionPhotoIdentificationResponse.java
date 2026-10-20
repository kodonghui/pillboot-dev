package org.semiproject.pillbadeumserver.image.domain;

import org.semiproject.pillbadeumserver.drug.dto.DrugSearchResponse;

import java.util.List;

/** 처방전/약봉투 사진에서 읽은 약명별 검색 후보입니다. */
public record PrescriptionPhotoIdentificationResponse(
        List<MedicineCandidates> medicines
) {
    public record MedicineCandidates(String readName, List<DrugSearchResponse> candidates) {
    }
}
