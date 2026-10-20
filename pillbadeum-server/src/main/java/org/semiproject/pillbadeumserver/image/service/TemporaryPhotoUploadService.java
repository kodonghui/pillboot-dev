package org.semiproject.pillbadeumserver.image.service;

import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import org.semiproject.pillbadeumserver.common.file.FileStorage;
import org.semiproject.pillbadeumserver.drug.service.ProfileAccessChecker;
import org.semiproject.pillbadeumserver.image.domain.TemporaryPhotoUploadResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/** 처방전/약봉투 사진을 검증해 서버의 temp 폴더에 임시 저장합니다. */
@Service
public class TemporaryPhotoUploadService {

    private static final String TEMP_DIRECTORY = "img_temp";
    private static final int MAX_PHOTOS = 3;

    private final FileStorage fileStorage;
    private final ProfileAccessChecker profileAccessChecker;

    public TemporaryPhotoUploadService(FileStorage fileStorage,
                                       ProfileAccessChecker profileAccessChecker) {
        this.fileStorage = fileStorage;
        this.profileAccessChecker = profileAccessChecker;
    }

    public TemporaryPhotoUploadResponse upload(Long profileId, List<MultipartFile> images) {
        profileAccessChecker.checkOwner(profileId);
        if (images == null || images.isEmpty() || images.size() > MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "사진은 1장 이상 3장 이하로 업로드해 주세요.");
        }

        List<String> savedNames = new ArrayList<>();
        try {
            for (MultipartFile image : images) {
                savedNames.add(fileStorage.saveImage(image, TEMP_DIRECTORY));
            }
            return new TemporaryPhotoUploadResponse(List.copyOf(savedNames));
        } catch (RuntimeException exception) {
            // 한 장 저장 후 다음 파일이 잘못되면 이미 저장한 임시 파일도 정리합니다.
            savedNames.forEach(name -> fileStorage.delete(name, TEMP_DIRECTORY));
            throw exception;
        }
    }
}
