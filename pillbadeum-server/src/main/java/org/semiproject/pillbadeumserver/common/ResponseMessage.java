package org.semiproject.pillbadeumserver.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/* 설명. 정상 응답일 때 내려줄 공통 응답 형식
 *  모든 API 가 같은 모양의 JSON 을 돌려주면 프론트엔드에서 응답을 꺼내는 코드가 한 가지로 통일된다.
 *  ----------------------------------------------------------------------------------
 *  {
 *    "httpStatus": 200,
 *    "message": "내 약 목록 조회 성공",
 *    "result": { "medications": [...] }      <- 실제 데이터는 항상 result 안에 담는다
 *  }
 *  ----------------------------------------------------------------------------------
 *  사용 예) return ResponseMessage.of(HttpStatus.OK, "프로필 목록 조회 성공", Map.of("profiles", profiles));
 * */
public class ResponseMessage {

    private int httpStatus;                 // HTTP 응답 상태코드
    private String message;                 // 응답 메시지
    private Map<String, Object> result;     // 응답 데이터

    public ResponseMessage() {
    }

    public ResponseMessage(int httpStatus, String message, Map<String, Object> result) {
        this.httpStatus = httpStatus;
        this.message = message;
        this.result = result;
    }

    /* 설명. 상태 코드와 본문의 httpStatus 를 한 번에 맞춰서 ResponseEntity 로 만들어 준다. */
    public static ResponseEntity<ResponseMessage> of(HttpStatus status, String message, Map<String, Object> result) {
        return ResponseEntity.status(status).body(new ResponseMessage(status.value(), message, result));
    }

    /* 설명. 돌려줄 데이터가 없는 응답 (삭제, 로그아웃 등) */
    public static ResponseEntity<ResponseMessage> of(HttpStatus status, String message) {
        return of(status, message, Map.of());
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "ResponseMessage{httpStatus=" + httpStatus + ", message='" + message + "', result=" + result + '}';
    }
}
