package org.semiproject.pillbadeumserver.image.domain;

import org.semiproject.pillbadeumserver.drug.dto.DrugSearchResponse;

import java.util.List;

/** 처방전/약봉투 사진에서 읽은 약명별 검색 후보입니다. */
public record PrescriptionPhotoIdentificationResponse(
        List<MedicineCandidates> medicines,
        List<Long> photoIds
) {
    public PrescriptionPhotoIdentificationResponse(List<MedicineCandidates> medicines) {
        this(medicines, List.of());
    }

    public PrescriptionPhotoIdentificationResponse {
        medicines = medicines == null ? List.of() : List.copyOf(medicines);
        photoIds = photoIds == null ? List.of() : List.copyOf(photoIds);
    }

    public record MedicineCandidates(String readName, List<DrugSearchResponse> candidates,
                                     String lookupStatus) {
        public MedicineCandidates(String readName, List<DrugSearchResponse> candidates) {
            this(readName, candidates, candidates == null || candidates.isEmpty() ? "NOT_FOUND" : "OK");
        }

        public MedicineCandidates {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
