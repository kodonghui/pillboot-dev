package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 업로드한 파일이 비었거나, 이미지가 아니거나, 허용하지 않는 확장자일 때 -> 400 Bad Request */
public class InvalidFileException extends BusinessException {

    public InvalidFileException() {
        super(ErrorCode.INVALID_FILE, "올바른 이미지 파일이 아닙니다.");
    }

    public InvalidFileException(String detail) {
        super(ErrorCode.INVALID_FILE, detail);
    }
}
