package org.semiproject.pillbadeumserver.image.domain;

import java.util.List;

/** 알약 사진에서 읽은 정보와 공공데이터 후보를 함께 반환합니다. */
public record PillPhotoIdentificationResponse(
        PillIdentification extracted,
        List<PillIdentificationCandidate> candidates,
        List<Long> photoIds,
        String lookupStatus
) {
    public PillPhotoIdentificationResponse(PillIdentification extracted,
                                          List<PillIdentificationCandidate> candidates) {
        this(extracted, candidates, List.of(), candidates == null || candidates.isEmpty() ? "NOT_FOUND" : "OK");
    }

    public PillPhotoIdentificationResponse(PillIdentification extracted,
                                          List<PillIdentificationCandidate> candidates,
                                          List<Long> photoIds) {
        this(extracted, candidates, photoIds,
                candidates == null || candidates.isEmpty() ? "NOT_FOUND" : "OK");
    }

    public PillPhotoIdentificationResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        photoIds = photoIds == null ? List.of() : List.copyOf(photoIds);
    }
}
