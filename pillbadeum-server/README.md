# pillbadeum-server

Java 21 / Spring Boot 4.1.1 / Gradle Wrapper 9.8.1 / MySQL 8.4.
팀 설계와 실제 학원 소스를 대조해 실행 환경·공통 코드·SQL을 정리했습니다.

[패키지 안내](docs/PACKAGE_GUIDE.md) · [SQL 안내](sql/README.md)

## 1. 개인 설정

명령은 이 폴더(`pillbadeum-server`)에서 실행합니다. 이미 있는 개인 `.env`는 덮어쓰지 마세요.

```powershell
Copy-Item .env.example .env
```

macOS/Linux: `cp .env.example .env`. Spring properties 형식이므로 값에 따옴표를 붙이지 않습니다.
`.env`는 Git에서 제외하고 `.env.example`만 공유합니다.

DB 연결은 `connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true`를 사용합니다.
`%2B09:00`은 `+09:00`의 URL 표현이며, MySQL 시간대 표가 비어 있어도 한국 시간의 세션을 만듭니다.
기존 `.env`의 `DB_URL`은 기본값보다 우선하므로, `Asia/Seoul`을 쓰던 개인 값도 직접 확인하세요.
Java·Jackson·로그의 `Asia/Seoul` 설정은 유지합니다.

| 항목 | 설정 |
| --- | --- |
| `DB_URL` | 예제의 pilldb 연결 주소. 포트 변경 시 함께 수정 |
| `DB_USERNAME`, `DB_PASSWORD` | 팀 로컬 계정 pill/pill. 공유 양식에는 의도적으로 비워 둠 |
| `JWT_SECRET` | Base64URL 256비트 이상 개인 서명키. 사용자 토큰과 다름 |
| `OPENAI_API_KEY` | 선택. 없으면 AI 호출만 503 |
| `OPENAI_CHAT_MODEL`, `OPENAI_VISION_MODEL` | AI 사용 시 계정에서 사용할 수 있는 Responses API 모델 ID 명시. 공란인 기능은 호출 전 503 |
| `PUBLIC_DATA_SERVICE_KEY` | 선택. 공공데이터포털 일반 인증키(Decoding) |
| `QDRANT_ENABLED` | 선택. Qdrant를 따로 띄운 사람만 true |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | 선택. 복약 알림 웹 푸시 키와 연락처(mailto:/https://). 셋을 함께 넣거나 모두 비움. 비우면 푸시 API만 503 |

개인 JWT 키 생성(PowerShell):

```powershell
$keyBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
[Convert]::ToBase64String($keyBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$rng.Dispose()
```

Python: `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
서명키가 없거나 짧으면 서버를 시작하지 않습니다. 실제 키는 공유하지 않습니다.

복약 알림 키(VAPID)는 P-256 키 한 쌍을 base64url로 넣습니다. 공개 키는 65바이트, 개인 키는 32바이트입니다.
`npx web-push generate-vapid-keys`로 만든 값을 그대로 넣어도 됩니다. 배포 서버 키는 한 번 정하면 바꾸지 않습니다.
키를 바꾸면 모든 브라우저가 알림을 다시 켜야 합니다. 공개 키와 개인 키가 짝이 아니면 서버를 시작하지 않습니다.
추론 옵션은 선택한 모델이 지원할 때만 `OPENAI_REASONING_EFFORT`로 지정합니다.
임베딩 기본 모델은 `text-embedding-3-small`입니다. 모델/차원을 바꾸면 Qdrant 컬렉션도 함께 맞춥니다.

## 2. MySQL 준비

**신규 DB 생성과 기존 10개 테이블 DB 변경은 다른 작업입니다.**
서버가 SQL을 자동 실행하거나 기존 DB를 초기화하지 않습니다.

### 신규 DB: 설치된 MySQL / Workbench

처음 설치는 서버 폴더에서 아래 명령 한 번으로 준비함. 00이 개발 DB/계정,01이 최신18개 테이블·150개 컬럼,13이 가입 약관을 준비함. 성분명은TEXT이며 구조 패치03~12·14는 신규 설치에 필요하지 않음.

```powershell
.\scripts\setup-local-db.ps1
```

기존 DB는 `.\scripts\setup-local-db.ps1 -UpgradeExisting`으로 누락된03~12와13·14를 적용함. 00~11까지 적용한 DB는12→13→14만 실행해도 됨. 최신01은 기존 컬럼을 업그레이드하는 파일이 아님. 클라이언트를 못 찾으면 `-MySqlClient 'C:\...\mysql.exe'`를 지정함. [신규/기존 DB별 안내](sql/README.md)에 파일 역할과 READY 확인을 정리함.

일정 API·삭제 동작은 [복약 일정 안내](docs/MEDICATION_SCHEDULE.md)를 참고하세요.
실시간 음성 대화 테스트는 [GPT-Live 안내](docs/MEDICATION_LIVE.md)를 참고하세요. local 실행 후 `/dev/medication-live`에서 시작합니다.
프론트 전달 기준은 [복약지도 연결 계약](docs/MEDICATION_FRONTEND_HANDOFF.md), 실행 방법과 확인 결과는 [백엔드 검증 기록](docs/MEDICATION_VERIFICATION.md)에 정리했습니다. 복약 완료 체크·실제 복용 기록은 구현 범위에서 제외합니다.
새 회원으로 복약지도만 테스트하려면 [테스트 계정 준비](docs/MEDICATION_TEST_ACCOUNT.md)의 명령 한 번으로 프로필·내 약·여러 상태의 일정을 함께 준비합니다.
Qdrant는 별도이며 MySQL `vector_point` 테이블은 만들지 않습니다.

### 신규 DB: Docker Compose

개인 `.env`에 `DB_USERNAME`, `DB_PASSWORD`, 별도의 `MYSQL_ROOT_PASSWORD`를 채웁니다.
설치된 MySQL과 포트가 겹치면 `MYSQL_PORT`와 `DB_URL`을 함께 바꾸세요.

```text
docker compose up -d --wait
docker compose exec mysql mysql -u pill -p pilldb -e "source /sql/01_PILLBADEUM_DB_SCRIPT.sql; source /sql/13_BASE_CONSENT.sql;"
```

데이터는 named volume에 보존합니다. `.env` 변경만으로 기존 volume의 계정 비밀번호가 바뀌지 않습니다.

### 이미 원본 10개 테이블이 있는 DB

이번 SQL은 비어 있는 개발 DB의 최초 설정용입니다. 기존 10개/15개 테이블 DB에 그대로 실행하지 마세요.
`01`은 기존 컬럼·제약을 변경하지 않습니다. 데이터가 있으면 백업 후 새 DB에서 먼저 확인하고
컬럼명·길이·필수값을 대조하여 별도로 옮깁니다. 이전 API 확장용 자동 업그레이드 파일은 제거했습니다.

### 선택: 샘플 데이터

01·13을 적용한 빈 개발 DB에서만 `02_PILLBADEUM_TEST_DATA.sql`을 한 번 실행합니다. 가입 약관은13에 있으며02를 생략해도 가입할 수 있습니다.
준비 스크립트는 `-IncludeTestData`를 지원합니다.
사진·후보·DUR은 초기 데이터가 없어도 됩니다.
샘플 약은 기존과 같이 ETC·OTC 각 1개입니다. 같은 분류의 약을 여러 개 저장할 수 있으며 ETC/OTC 이외의 분류는 CHECK로 거절합니다.
공공데이터의 분류 표기가 다르면 약 저장 기능에서 ETC/OTC로 변환해야 합니다.
샘플 품목 코드/약 이름의 공식 일치 여부와 샘플 비밀번호 평문은 검증하지 않았습니다.
화면·관계 확인용 샘플을 실제 API·로그인 성공용 데이터로 간주하지 않습니다.

## 3. 서버 실행과 인증

```powershell
.\gradlew.bat bootRun
```

macOS/Linux: `./gradlew bootRun`.

- Health: http://localhost:8080/actuator/health
- 로컬 Swagger: http://localhost:8080/swagger-ui/index.html
- OpenAPI: http://localhost:8080/v3/api-docs
- 프런트 기본 Origin: http://localhost:5173. 추가 주소는 `CORS_ALLOWED_ORIGINS`로 지정

사용자가 “인증은 Bearer를 사용한다”고 확인한 내용과 수업 구현을 기준으로
**Bearer JWT / STATELESS / 1시간 / Refresh Token·차단목록 없음**을 적용합니다.
로그인 담당자는 발급 토큰을 JSON 응답으로 제공하고, 프런트는 `Authorization: Bearer <토큰>`을 보냅니다.
로그아웃은 프런트가 토큰을 제거하며 복사된 토큰은 원래 만료 시각까지 유효합니다.
Refresh Token은 학원 보안 자료의 선택 확장 부록에 있습니다. 현재 프로젝트의 1시간 만료·재로그인 정책에 따라 도입하지 않습니다.
공개 API는 GET 약관·이메일 확인과 POST 가입·로그인·로그아웃 5개이며 정확한 경로는 SecurityConfig에 있습니다.

**API와 Functional Spec ver.4에는 ACCESS_TOKEN 쿠키 방식이 적혀 있습니다.**
이 쿠키 문구는 이번 적용 기준과 다르므로 두 설계문서의 수정 대상입니다.
이 PR은 기존 설계문서를 직접 덮어쓰지 않고 결정 기록과 변경 필요 범위를 남깁니다.
쿠키를 채택하면 필터·CORS credentials·CSRF·Swagger·프런트·로그아웃을 함께 변경해야 합니다.
쿠키를 받는 설정 없이 csrf.disable만 복사해서 사용하지 않습니다.
현재 업무 로그인/로그아웃 API 자체는 담당자가 구현해야 합니다.

## 4. 선택: Qdrant

기본 off입니다. 아래처럼 각자 Qdrant를 실행한 뒤 개인 .env에서 켭니다. 정상 채팅의 질문·답변·요약을 임베딩해 저장하고 과거 요약을 검색합니다. 다른 사람의 볼륨을 복사하지 않습니다.

```text
docker compose -f compose.qdrant.yaml up -d
```

개인 `.env`에 `QDRANT_ENABLED=true`를 넣고 재시작합니다.
대시보드: http://localhost:6333/dashboard. 앱 gRPC는 6334입니다.
`docker compose -f compose.qdrant.yaml down`으로 끄더라도 volume은 남습니다.
MySQL Compose와 분리되어 Qdrant만 사용할 때 MySQL 비밀번호 설정이 필요 없습니다.
MySQL이 원본이며 Qdrant 장애 시 최근 대화/요약으로 답하는 fallback은 채팅 서비스에서 구현합니다.

## 설정과 기능 담당 경계

- local 프로필만 `.env`를 읽고 Swagger를 켭니다. prod는 실제 환경변수를 주입하며 Swagger는 기본 off입니다.
- JPA `ddl-auto: none`, `open-in-view: false`, SQL 자동 실행 off를 유지합니다.
- JVM·Jackson·JDBC·Compose·로그의 한국 시간 설정을 유지합니다. JSON 오프셋은 DTO에서 표현합니다.
- common으로 통합하고 업무 패키지 6개를 보존합니다. 오류 응답은 `{code,message}`, 정상 응답은 업무 DTO입니다.
- 외부 호출은 연결2초, 공공데이터10초(PUBLIC_DATA_READ_TIMEOUT_MS로 조정), 분류·임베딩·구조화 응답10초, 요약 자체 마감·음성 합성30초, 대화 답변 생성·처방전 판독60초, 낱알 판독85초입니다. 등록 후DUR 선조회는 한 작업·최대2호출로 제한하며 foreground용 자리를 남깁니다.
- 공공데이터·임베딩만 일시 오류를 500ms 뒤 1회 재시도합니다. 생성 요청은 자동 재시도하지 않습니다.
- 외부 동시 호출 기본4개/대기10초는 조정 가능한 기본값이며 서비스 보장치가 아닙니다. 대기 초과는503 EXTERNAL_API_FAILED와 재시도 안내를 반환합니다.
- 사진 식별·대화의 외부 작업 전체 마감은85초이며 업로드·DB·프록시를 포함한 절대 응답 시간 보장은 아닙니다.
- 백그라운드 처리는 수업처럼 Boot의 applicationTaskExecutor를 재사용할 수 있습니다. 커밋 이후 호출합니다.
- 사진 member별/채팅 session별 중복 방지는 해당 서비스에서 구현합니다. 제거한 URI별 공통 락을 대신 쓰지 않습니다.
- FileStorage는 기본 확장자·헤더 검사와 파일 IO 도구입니다. 실제 내용 검사/2048px/JPEG85/EXIF 제거는 아직 없습니다.
- 처방전 파일은 공개 URL로 노출하지 않습니다. 소유권 확인, 임시 행·파일 삭제, 예약 알림·VAPID 발송은 기능 담당 범위입니다.
- 보류된 복약 완료/미복용/100일 삭제나 사용하지 않는 캐시 스케줄러를 미리 추가하지 않습니다.

## 검증

```powershell
.\gradlew.bat test bootJar
```

기본 테스트는 MySQL 없이 실제 보안 필터·CORS·공통 오류 및 로컬 HTTP 외부 호출을 검증합니다.
실제 DB 검증은 별도 **pill_test** DB에서만 실행합니다.
처음 실행하기 전에 관리자 연결에서 테스트 DB와 개발 계정 권한을 준비합니다.
00번과 MySQL Compose는 업무 DB인 pilldb만 준비하므로 이 단계는 별도입니다.

```sql
CREATE DATABASE IF NOT EXISTS pill_test
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON pill_test.* TO 'pill'@'%';
```

```powershell
$env:MYSQL_TEST_URL = 'jdbc:mysql://localhost:3306/pill_test?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true'
$env:MYSQL_TEST_USER = 'pill'
$env:MYSQL_TEST_PASSWORD = '<테스트 DB 비밀번호>'
.\gradlew.bat test bootJar
```

MYSQL_TEST_URL이 없으면 DB 검증만 생략됩니다. CI는 실제 MySQL 8.4에서 이를 실행합니다.
테스트 컨트롤러·공개 테스트 키는 실행 JAR에 포함되지 않습니다. H2/Flyway는 추가하지 않습니다.
실제 외부 API 키·공식 품목 코드·OpenAI 모델 사용권한·Qdrant CRUD·푸시 발송은 별도 실연동 검증이 필요합니다.


## 사진 등록 통합

REG-002~007의 정식 경로·응답·SQL 적용·확인 절차는 [사진 등록 안내](docs/PHOTO_REGISTRATION.md)에 기록함. 앞뒤 사진/실제 품목코드 연결에는 05 패치가 필요함. 기존 무저장 `/api/images/*` 보조 API와 새 후보 보존 API의 수명은 서로 다름.
