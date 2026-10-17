# pillbadeum-server

Java 21 / Spring Boot 4.1.1 / Gradle Wrapper 9.8.1 / MySQL 8.4.
팀 설계와 실제 학원 소스를 대조해 실행 환경·공통 코드·SQL을 정리했습니다.

검토 근거와 결정 기록 · [패키지 안내](docs/PACKAGE_GUIDE.md) · [SQL 안내](sql/README.md)

실제 수업 소스 대조 결과: 제공된 9개 수업 폴더에서 기본 구현과 선택 확장을 구분했습니다.

## 1. 개인 설정

명령은 이 폴더(`pillbadeum-server`)에서 실행합니다. 이미 있는 개인 `.env`는 덮어쓰지 마세요.

```powershell
Copy-Item .env.example .env
```

macOS/Linux: `cp .env.example .env`. Spring properties 형식이므로 값에 따옴표를 붙이지 않습니다.
`.env`는 Git에서 제외하고 `.env.example`만 공유합니다.

| 항목 | 설정 |
| --- | --- |
| `DB_URL` | 예제의 pilldb 연결 주소. 포트 변경 시 함께 수정 |
| `DB_USERNAME`, `DB_PASSWORD` | 팀 로컬 계정 pill/pill. 공유 양식에는 의도적으로 비워 둠 |
| `JWT_SECRET` | Base64URL 256비트 이상 개인 서명키. 사용자 토큰과 다름 |
| `OPENAI_API_KEY` | 선택. 없으면 AI 호출만 503 |
| `OPENAI_CHAT_MODEL`, `OPENAI_VISION_MODEL` | AI 사용 시 계정에서 사용할 수 있는 Responses API 모델 ID 명시. 공란인 기능은 호출 전 503 |
| `PUBLIC_DATA_SERVICE_KEY` | 선택. 공공데이터포털 일반 인증키(Decoding) |
| `QDRANT_ENABLED` | 선택. Qdrant를 따로 띄운 사람만 true |

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
추론 옵션은 선택한 모델이 지원할 때만 `OPENAI_REASONING_EFFORT`로 지정합니다.
임베딩 기본 모델은 `text-embedding-3-small`입니다. 모델/차원을 바꾸면 Qdrant 컬렉션도 함께 맞춥니다.

## 2. MySQL 준비

**신규 DB 생성과 기존 10개 테이블 DB 변경은 다른 작업입니다.**
서버가 SQL을 자동 실행하거나 기존 DB를 초기화하지 않습니다.

### 신규 DB: 설치된 MySQL / Workbench

관리자 연결에서 `sql/00_PILLBADEUM_DB_CREAT.sql` 실행 후 pilldb를 선택하고
`sql/01_PILLBADEUM_DB_SCRIPT.sql`을 실행합니다. 기존 계정 비밀번호는 변경하지 않습니다.

```powershell
.\scripts\setup-local-db.ps1
```

클라이언트를 못 찾으면 `-MySqlClient 'C:\...\mysql.exe'`를 지정합니다.
스키마는 기존 10개 + 첨부 ERD 누락 4개 + API의 푸시 구독 1개로 **MySQL 15개**입니다.
Qdrant는 별도이며 MySQL `vector_point` 테이블은 만들지 않습니다.

### 신규 DB: Docker Compose

개인 `.env`에 `DB_USERNAME`, `DB_PASSWORD`, 별도의 `MYSQL_ROOT_PASSWORD`를 채웁니다.
설치된 MySQL과 포트가 겹치면 `MYSQL_PORT`와 `DB_URL`을 함께 바꾸세요.

```text
docker compose up -d --wait
docker compose exec mysql mysql -u pill -p pilldb -e "source /sql/01_PILLBADEUM_DB_SCRIPT.sql"
```

데이터는 named volume에 보존합니다. `.env` 변경만으로 기존 volume의 계정 비밀번호가 바뀌지 않습니다.

### 이미 원본 10개 테이블이 있는 DB

백업 후 [SQL 안내](sql/README.md)의 사전 조회와 `03_PILLBADEUM_UPGRADE_FROM_10_TABLES.sql`을 사용합니다.
`01` 재실행은 기존 컬럼·제약을 변경하지 않습니다. 준비 스크립트도 업그레이드를 자동 적용하지 않습니다.
`03`은 aa15bf7 원본 상태 전용 1회 변경이며, 중복이나 예상하지 못한 채팅 순서가 있으면 중단합니다.
기존 DB에서 `02` 샘플을 다시 실행하지 마세요.

### 선택: 샘플 데이터

빈 개발 DB에서만 `02_PILLBADEUM_TEST_DATA.sql`을 한 번 실행합니다.
준비 스크립트는 `-IncludeTestData`를 지원합니다.
사진·후보·DUR·푸시 구독은 초기 데이터가 없어도 됩니다.
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

기존 사용자 추가 연결 설정을 유지합니다. 기본 off이며 컬렉션 CRUD/검색 기능은 아직 구현하지 않았습니다.

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
- 외부 호출은 연결 2초, 공공데이터 5초, 분류/임베딩 10초, 비전/답변/요약 30초입니다.
- 공공데이터·임베딩만 일시 오류를 500ms 뒤 1회 재시도합니다. 생성 요청은 자동 재시도하지 않습니다.
- 외부 동시 호출 기본 4개/대기 1초는 이 작업의 조정 가능한 기본값이며 서비스 보장치가 아닙니다.
- 여러 작업을 합친 전체 90초 제한은 담당 서비스의 남은 deadline 계산으로 구현해야 합니다.
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

```powershell
$env:MYSQL_TEST_URL = 'jdbc:mysql://localhost:3306/pill_test?connectionTimeZone=Asia/Seoul&forceConnectionTimeZoneToSession=true'
$env:MYSQL_TEST_USER = 'pill'
$env:MYSQL_TEST_PASSWORD = '<테스트 DB 비밀번호>'
.\gradlew.bat test bootJar
```

MYSQL_TEST_URL이 없으면 DB 검증만 생략됩니다. CI는 실제 MySQL 8.4에서 이를 실행합니다.
테스트 컨트롤러·공개 테스트 키는 실행 JAR에 포함되지 않습니다. H2/Flyway는 추가하지 않습니다.
실제 외부 API 키·공식 품목 코드·OpenAI 모델 사용권한·Qdrant CRUD·푸시 발송은 별도 실연동 검증이 필요합니다.
