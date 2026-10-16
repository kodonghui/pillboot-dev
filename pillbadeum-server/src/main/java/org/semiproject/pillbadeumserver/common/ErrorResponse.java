package org.semiproject.pillbadeumserver.common;

/* 설명. 예외 상황일 때 내려줄 공통 응답 형식
 *  정상 응답은 ResponseMessage, 예외 응답은 ErrorResponse 로 모양을 나눠 둔다.
 *  ----------------------------------------------------------------------------------
 *  - code        : 애플리케이션이 정한 에러 코드 (ErrorCode 의 code. 예: COMMON_005)
 *  - description : 어떤 상황인지에 대한 짧은 설명 (예: 조회 실패)
 *  - detail      : 화면에 보여줄 구체적인 내용 (예: 존재하지 않는 프로필입니다.)
 *  ----------------------------------------------------------------------------------
 *  주의. detail 에는 SQL, 내부 클래스 이름, API 키 같은 내부 정보를 넣지 않는다. (COMMON-004)
 * */
public class ErrorResponse {

    private String code;
    private String description;
    private String detail;

    public ErrorResponse() {
    }

    public ErrorResponse(String code, String description, String detail) {
        this.code = code;
        this.description = description;
        this.detail = detail;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    @Override
    public String toString() {
        return "ErrorResponse{code='" + code + "', description='" + description + "', detail='" + detail + "'}";
    }
}
