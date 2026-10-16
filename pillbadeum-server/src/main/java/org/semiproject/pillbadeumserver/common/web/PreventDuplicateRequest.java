package org.semiproject.pillbadeumserver.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/* 설명. 같은 사용자가 같은 API 를 처리가 끝나기 전에 또 보내면 막는다. (COMMON-002)
 *  ----------------------------------------------------------------------------------
 *  '저장' 버튼을 두 번 눌러 약이 두 개 저장되거나, 사진 식별 · AI 답변처럼 오래 걸리고 비용이 드는 요청이
 *  두 번 나가는 것을 서버에서 한 번 더 막는다. (화면의 '처리 중' 표시 · 버튼 비활성화는 프론트에서 한다)
 *  ----------------------------------------------------------------------------------
 *  사용 예) 컨트롤러 메서드에 붙인다.
 *      @PreventDuplicateRequest
 *      @PostMapping("/api/images/pills/identify")
 *      public ResponseEntity<ResponseMessage> identify(...) { ... }
 *  두 번째 요청은 409 (COMMON_007) 로 응답한다.
 * */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreventDuplicateRequest {
}
