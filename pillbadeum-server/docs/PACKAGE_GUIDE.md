# 패키지와 공통 코드

기본 패키지는 `org.semiproject.pillbadeumserver`이며 기존 경로를 유지합니다.

```text
PillbadeumServerApplication
common/
  ai/          OpenAI 텍스트·이미지·스트리밍·임베딩 클라이언트
  config/      CORS·JPA 감사·시간대·캐시·스케줄링·Swagger·MVC 인터셉터·Qdrant 연결(기본 꺼짐)
  entity/      BaseTimeEntity
  exception/   ErrorCode·BusinessException·GlobalExceptionHandler 및 개별 예외
  external/    공공데이터 클라이언트·HTTP 시간 제한·외부 실패 변환
  file/        이미지 저장·검사·조회·삭제
  security/    JWT 발급·검증·인증 필터·회원 확인·로그아웃 목록
  util/        SecretMasker
  web/         중복 요청 방지 어노테이션·인터셉터
  ErrorResponse / ResponseMessage / PageResponse
global/
  config/      CORS 값 검증·Clock·요청 ID와 비동기 로그 컨텍스트
  filter/      RequestIdFilter
  response/    기존 PageResponse 경로 보존
  exception/   패키지 위치 보존. 전역 처리기는 common/exception 하나를 사용
member/        회원·로그인·약관
profile/       본인·가족 프로필
drug/          약 검색·정보·내 약
image/         이미지·OCR·약 식별
chat/          대화·AI·SSE
medication/    복약 일정·완료·알림
```

업무 패키지는 각각 `controller/service/domain/dto`, 테스트 폴더는 `.gitkeep`을 유지합니다.
회원가입·로그인·업무 Controller/Entity는 추가하지 않았습니다.

## 중복 설정의 담당 위치

| 역할 | 등록 위치 |
| --- | --- |
| JWT SecurityFilterChain·PasswordEncoder | `common.security.SecurityConfig` |
| CORS | `common.config.CorsConfig` + `global.config.CorsProperties` |
| 중복 요청 인터셉터 | `common.config.WebConfig` |
| 요청 ID·MDC 전달 | `global.config.WebConfig` (`globalWebConfig`라는 별도 빈 이름) |
| Swagger Bearer 인증 명세 | `common.config.OpenApiConfig` |
| 오류 응답 | `common.exception.GlobalExceptionHandler`, JWT 401·403 처리기 |

기존 JWT 없는 SecurityConfig, 별도 OpenApiConfig, ProblemDetail 처리기는 중복 등록하지 않습니다.
기존 예외 코드와 `{code, description, detail}` 응답을 사용합니다.
새 공통 프레임워크나 로그인·AI 업무 기능은 추가하지 않습니다.
