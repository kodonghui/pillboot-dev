# 패키지와 공통 코드

기본 패키지는 `org.semiproject.pillbadeumserver`입니다. 기존 업무 패키지 6개와
각 controller/service/domain/dto 및 테스트 자리의 `.gitkeep`을 보존합니다.

```text
PillbadeumServerApplication
common/
  ai/          OpenAI Responses·임베딩
  config/      CORS·JPA 감사·한국 시간·스케줄링·Swagger·Qdrant·요청 추적 등록
  entity/      BaseTimeEntity
  exception/   기존 BusinessException 계층·ErrorCode·GlobalExceptionHandler
  external/    RestClient 시간 제한·한정 재시도·호출 상한·공공데이터
  file/        기본 이미지 검사·파일 저장/조회/삭제
  security/    JWT 발급·검증·필터·401/403·회원/소유권 확인
  util/        SecretMasker
  web/         RequestIdFilter
  ErrorResponse / PageResponse
member/        회원·로그인·약관
profile/       본인·가족 프로필
drug/          약 검색·정보·내 약
image/         이미지·OCR·약 식별
chat/          대화·AI 일괄 응답
medication/    복약 일정·알림
```

공통 코드는 common 한 곳으로 통합했습니다. 중복 global 패키지, PageResponse, Clock,
미사용 ResponseMessage와 캐시 삭제 작업을 제거했습니다. 기존 원본의 요청 ID/MDC 전파와
기존 원본의 전역 예외 처리 역할은 남깁니다. 새로운 공통 계층·모듈을 추가하지 않습니다.

| 역할 | 위치 |
| --- | --- |
| JWT/PasswordEncoder/정확한 공개 경로 | common.security.SecurityConfig |
| CORS 값과 검증 | common.config.CorsConfig, CorsProperties |
| 요청 ID/MDC 작업 전달 | common.config.WebConfig, common.web.RequestIdFilter |
| Swagger 인증 | common.config.OpenApiConfig |
| 오류 JSON/상태 | common.exception.GlobalExceptionHandler + 보안401/403 처리기 |
| 신규/기존 DB 적용 | sql/README.md |

오류는 `{code,message}`이고 내부 SQL/외부 응답 원문은 숨깁니다. 예상한 업무 중복은 서비스에서
해당 ErrorCode를 명시합니다. 정체를 모르는 DB 제약 실패를 모두 사용자 중복409로 바꾸지 않습니다.
정상 응답은 API의 DTO를 직접 반환하고 204는 본문 없이 보냅니다.
기존 PageResponse는 API가 요구한 페이지 구조에 맞는 경우만 사용합니다.

업무 로그인/엔티티/이미지 변환/예약 알림/채팅 오케스트레이션을 구현한 것은 아닙니다.
필요한 비동기는 Boot applicationTaskExecutor를 재사용합니다. 사진 member 단위/채팅방 단위
중복 요청 제어는 해당 서비스의 처리 범위에서 정리합니다. 전 URI를 묶는 추상 락을 미리 두지 않습니다.
