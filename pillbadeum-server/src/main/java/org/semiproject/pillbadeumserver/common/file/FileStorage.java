package org.semiproject.pillbadeumserver.common.file;

import org.semiproject.pillbadeumserver.common.exception.InvalidFileException;
import org.semiproject.pillbadeumserver.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/* 설명. 업로드 이미지를 프로젝트 바깥 폴더에 저장 · 조회 · 삭제하는 부품 (REG-002, REG-006, PROF-001)
 *  ----------------------------------------------------------------------------------
 *  저장 위치 : application.yaml 의 file.upload.* (OS 별 경로) / 하위 폴더
 *      예) C:/myWs/pillbadeum-uploads/prescriptions/3f2a...c1.jpg
 *  ERD의 photo.temp_path에는 UUID 이름의 임시 경로를 기록한다. 원래 파일명은 저장하지 않는다.
 *  ----------------------------------------------------------------------------------
 *  사용 예)
 *      String savedName = fileStorage.saveImage(file, FileStorage.DIR_PILL);          // 저장
 *      byte[] bytes     = fileStorage.readBytes(savedName, FileStorage.DIR_PILL);     // OpenAI 로 보낼 때
 *      Resource image   = fileStorage.loadAsResource(savedName, FileStorage.DIR_PILL);// 화면에 내려줄 때 (본인 확인 후)
 *      fileStorage.delete(savedName, FileStorage.DIR_PILL);                           // 삭제
 * */
@Component
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    /* 목차. 하위 폴더 이름 (새 폴더가 필요하면 여기에 상수로 추가한다) */
    public static final String DIR_PRESCRIPTION = "prescriptions";   // 처방전 · 약봉투 사진 (REG-002)
    public static final String DIR_PILL = "pills";                   // 알약 앞 · 뒤 사진 (REG-006)
    public static final String DIR_PROFILE = "profiles";             // 프로필 사진 (PROF-001)
    public static final String DIR_TEMP = "img_temp";                 // 식별용 임시 이미지 (REQ-18)

    public enum DeleteResult { DELETED, NOT_FOUND, FAILED }

    /* 설명. 허용하는 이미지 확장자
     *  OpenAI 이미지 입력이 지원하는 형식(jpg, png, webp)만 허용한다.
     *  아이폰의 HEIC 는 브라우저가 업로드할 때 대부분 JPEG 로 바꿔서 보낸다.
     * */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    @Value("${file.upload.windows.path}")
    private String windowsPath;

    @Value("${file.upload.mac.path}")
    private String macPath;

    /* 설명. 현재 OS 에 맞는 업로드 기본 경로 */
    public String getBasePath() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return osName.contains("win") ? windowsPath : macPath;
    }

    /* 목차. 1. 이미지 검사 후 저장. 저장된 파일명을 돌려준다. */
    public String saveImage(MultipartFile file, String subDirectory) {

        String extension = validateImage(file);
        Path directory = Paths.get(getBasePath(), subDirectory);
        String savedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;

        try {
            Files.createDirectories(directory);
            file.transferTo(directory.resolve(savedName));
            return savedName;
        } catch (IOException e) {
            // transferTo가 파일을 일부 쓴 뒤 실패하면 호출자는 파일명을 받지 못한다. 여기서 직접 정리한다.
            if (DIR_TEMP.equals(subDirectory)) deleteTemporaryImage(savedName);
            throw new UncheckedIOException("파일 저장 실패: " + directory, e);
        } catch (RuntimeException e) {
            if (DIR_TEMP.equals(subDirectory)) deleteTemporaryImage(savedName);
            throw e;
        }
    }

    /* 목차. 2. 이미지 검사 (비었는지, 확장자, Content-Type). 통과하면 소문자 확장자를 돌려준다. */
    public String validateImage(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("업로드할 이미지가 없습니다.");
        }

        String originalName = file.getOriginalFilename();
        String extension = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";

        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw new InvalidFileException("jpg, jpeg, png, webp 이미지만 올릴 수 있습니다.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidFileException("이미지 파일만 올릴 수 있습니다.");
        }

        return extension.equals("jpeg") ? "jpg" : extension;
    }

    /* 목차. 3. 저장된 파일 읽기 (OpenAI 이미지 식별에 보낼 때) */
    public byte[] readBytes(String savedName, String subDirectory) {
        try {
            return Files.readAllBytes(existingPath(savedName, subDirectory));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + savedName, e);
        }
    }

    /* 목차. 4. 저장된 파일을 Resource 로 (컨트롤러에서 이미지 응답으로 내려줄 때)
     *  주의. 내려주기 전에 반드시 본인의 이미지인지 SecurityUtil.checkOwner() 로 확인한다.
     * */
    public Resource loadAsResource(String savedName, String subDirectory) {
        return new FileSystemResource(existingPath(savedName, subDirectory));
    }

    /* 목차. 5. 이미지의 MIME 타입 (OpenAI 로 보낼 data URL, 응답 Content-Type 에 쓴다) */
    public String getMimeType(String savedName) {
        String lower = savedName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /* 목차. 6. 저장된 파일 삭제. 없으면 아무 일도 하지 않는다. */
    public void delete(String savedName, String subDirectory) {

        if (savedName == null || savedName.isBlank()) {
            return;
        }

        try {
            Files.deleteIfExists(resolve(savedName, subDirectory));
        } catch (IOException e) {
            log.warn("[FileStorage] 파일 삭제 실패 : {}/{}", subDirectory, savedName);
        }
    }

    /**
     * 서버가 발급한 임시 이미지 한 개만 지운다. 디렉터리·링크·다른 경로는 삭제하지 않는다.
     * 저장/식별 결과를 파일 IO 실패로 뒤집지 않도록 실패는 결과값으로 돌려준다.
     */
    public DeleteResult deleteTemporaryImage(String savedName) {
        if (savedName == null || !savedName.matches("[0-9a-f]{32}\\.(jpg|png|webp)")) {
            return DeleteResult.FAILED;
        }
        try {
            Path base = Paths.get(getBasePath()).toAbsolutePath().normalize();
            Path directory = base.resolve(DIR_TEMP);
            Path target = directory.resolve(savedName).normalize();
            if (!target.getParent().equals(directory) || !target.startsWith(directory)) {
                return DeleteResult.FAILED;
            }
            // img_temp 자체가 링크/Windows junction으로 바뀌어 다른 폴더를 가리키는 경우도 차단한다.
            if (Files.isSymbolicLink(directory)
                    || !directory.toRealPath().equals(base.toRealPath().resolve(DIR_TEMP))) {
                return DeleteResult.FAILED;
            }
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) return DeleteResult.FAILED;
            return Files.deleteIfExists(target) ? DeleteResult.DELETED : DeleteResult.NOT_FOUND;
        } catch (NoSuchFileException e) {
            return DeleteResult.NOT_FOUND;
        } catch (IOException | SecurityException e) {
            log.warn("[FileStorage] 임시 이미지 삭제 실패 type={}", e.getClass().getSimpleName());
            return DeleteResult.FAILED;
        }
    }

    /* 설명. 파일명에 '..' 이나 경로 구분자가 들어오면 다른 폴더의 파일을 읽을 수 있으므로 막는다. */
    private Path resolve(String savedName, String subDirectory) {

        if (savedName.contains("..") || savedName.contains("/") || savedName.contains("\\")) {
            throw new InvalidFileException("잘못된 파일 이름입니다.");
        }
        return Paths.get(getBasePath(), subDirectory).resolve(savedName);
    }

    private Path existingPath(String savedName, String subDirectory) {

        Path path = resolve(savedName, subDirectory);
        if (!Files.exists(path)) {
            throw new NotFoundException("이미지 파일을 찾을 수 없습니다.");
        }
        return path;
    }
}
