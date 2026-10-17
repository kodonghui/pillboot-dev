package org.semiproject.pillbadeumserver.common;

/* API 명세의 공통 오류 응답. message에는 화면에 표시해도 되는 문구만 넣는다.
 * 정상 응답은 각 API의 DTO를 직접 반환하고, 204 응답에는 본문을 넣지 않는다.
 */
public class ErrorResponse {

    private String code;
    private String message;

    public ErrorResponse() {
    }

    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "ErrorResponse{code='" + code + "', message='" + message + "'}";
    }
}
