package org.semiproject.pillbadeumserver.image.domain;

import java.util.List;

/** 알약 사진에서 읽은 정보와 공공데이터 후보를 함께 반환합니다. */
public record PillPhotoIdentificationResponse(
        PillIdentification extracted,
        List<PillIdentificationCandidate> candidates
) {
}
