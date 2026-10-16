package org.semiproject.pillbadeumserver.common.exception;

/* 설명. 외부 API(OpenAI, 공공데이터포털 의약품 · DUR 등) 호출이 실패했을 때 (COMMON-001)
 *  ----------------------------------------------------------------------------------
 *  - 실패     : 502 Bad Gateway      -> 우리 서버는 정상인데 우리가 호출한 서버가 실패했다
 *  - 시간 초과 : 504 Gateway Timeout  -> 우리가 호출한 서버가 정해진 시간 안에 응답하지 않았다
 *  ----------------------------------------------------------------------------------
 *  보통은 직접 만들지 않는다. ExternalApiCaller.call() 로 외부 API 를 부르면 알아서 이 예외로 바꿔 준다.
 *  주의. detail 은 화면에 그대로 나가므로 요청 주소(서비스 키 포함)나 응답 원문을 넣지 않는다.
 * */
public class ExternalApiException extends BusinessException {

    // 어떤 외부 API 였는지 (로그 · 분기용. 예: "OpenAI", "공공데이터포털")
    private final String apiName;

    private ExternalApiException(ErrorCode errorCode, String apiName, String detail, Throwable cause) {
        super(errorCode, detail, cause);
        this.apiName = apiName;
    }

    /* 설명. 외부 API 가 오류를 돌려줬거나 연결할 수 없을 때 */
    public static ExternalApiException failed(String apiName, String reason, Throwable cause) {
        return new ExternalApiException(ErrorCode.EXTERNAL_API_FAILED, apiName,
                apiName + " 호출에 실패했습니다. " + reason, cause);
    }

    /* 설명. 외부 API 가 정해진 시간 안에 응답하지 않았을 때 */
    public static ExternalApiException timeout(String apiName, Throwable cause) {
        return new ExternalApiException(ErrorCode.EXTERNAL_API_TIMEOUT, apiName,
                apiName + " 응답이 늦어지고 있습니다. 잠시 후 다시 시도해주세요.", cause);
    }

    public String getApiName() {
        return apiName;
    }

    public boolean isTimeout() {
        return getErrorCode() == ErrorCode.EXTERNAL_API_TIMEOUT;
    }
}
