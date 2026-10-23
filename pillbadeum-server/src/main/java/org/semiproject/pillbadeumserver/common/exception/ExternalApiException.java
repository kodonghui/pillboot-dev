package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 외부 API(OpenAI, 공공데이터포털 의약품 · DUR 등) 호출이 실패했을 때 (COMMON-001)
 *  ----------------------------------------------------------------------------------
 *  - 실패     : 503 EXTERNAL_API_FAILED
 *  - 시간 초과 : 503 EXTERNAL_TIMEOUT
 *  ----------------------------------------------------------------------------------
 *  보통은 직접 만들지 않는다. ExternalApiCaller.call() 로 외부 API 를 부르면 알아서 이 예외로 바꿔 준다.
 *  주의. 외부 응답 원문·주소·예외 메시지는 사용자 응답에 넣지 않는다.
 * */
public class ExternalApiException extends BusinessException {

    // 어떤 외부 API 였는지 (로그 · 분기용. 예: "OpenAI", "공공데이터포털")
    private final String apiName;
    private final String failureReason;

    private ExternalApiException(ErrorCode errorCode, String apiName, String detail, String failureReason, Throwable cause) {
        super(errorCode, detail, cause);
        this.apiName = apiName;
        // 호출자가 작성한 고정 진단 사유만 받음. 외부 본문·URL·입력값을 전달하지 않음.
        this.failureReason = failureReason;
    }

    /* 설명. 외부 API 가 오류를 돌려줬거나 연결할 수 없을 때 */
    public static ExternalApiException failed(String apiName, String reason, Throwable cause) {
        return new ExternalApiException(ErrorCode.EXTERNAL_API_FAILED, apiName,
                apiName + " 호출에 실패했습니다. 잠시 후 다시 시도해주세요.", reason, cause);
    }

    public static ExternalApiException busy(String apiName) {
        return new ExternalApiException(ErrorCode.EXTERNAL_API_FAILED, apiName,
                "요청이 많습니다. 잠시 후 다시 시도해주세요.", "동시 호출 대기 초과", null);
    }

    /* 설명. 외부 API 가 정해진 시간 안에 응답하지 않았을 때 */
    public static ExternalApiException timeout(String apiName, Throwable cause) {
        return new ExternalApiException(ErrorCode.EXTERNAL_API_TIMEOUT, apiName,
                apiName + " 응답이 늦어지고 있습니다. 잠시 후 다시 시도해주세요.", "응답 대기 시간 초과", cause);
    }

    public String getApiName() {
        return apiName;
    }

    public String getFailureReason() { return failureReason; }

    public boolean isTimeout() {
        return getErrorCode() == ErrorCode.EXTERNAL_API_TIMEOUT;
    }
}
