package org.semiproject.pillbadeumserver.image.domain;

import java.util.List;

/** 서버 temp 폴더에 저장된 사진의 식별자만 반환합니다. */
public record TemporaryPhotoUploadResponse(List<String> photoIds) {
}
