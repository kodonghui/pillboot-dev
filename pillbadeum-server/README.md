# pillbadeum-server

Java 21 / Spring Boot 4.1.1 / Gradle Wrapper 9.8.1 / MySQL 8.4.

기존 `common` 공통 구현과 팀원의 업무 패키지·개발환경 구성을 통합했습니다.
업무 Controller/Entity는 아직 없으며, 회원가입·로그인 API는 담당자가 구현합니다.
JWT 발급·검증, OpenAI·공공데이터 호출, 파일 저장 등 기존 공통 도구는 유지했습니다.

## 1. 개인 설정

명령어는 이 폴더(`pillbadeum-server`)에서 실행합니다.

```powershell
Copy-Item .env.example .env
```

macOS/Linux에서는 `cp .env.example .env`를 사용합니다. 이미 `.env`가 있으면 덮어쓰지 않습니다.

개인 `.env`의 다음 항목을 채우세요. 값에 따옴표를 붙이지 않습니다.

| 항목 | 내용 |
| --- | --- |
| `DB_URL` | 예제의 `pilldb` 주소. DB 포트를 바꾸면 함께 수정 |
| `DB_USERNAME`, `DB_PASSWORD` | 본인의 MySQL 계정. 팀 로컬 계정은 `pill` |
| `JWT_SECRET` | 아래 방법으로 생성하는 Base64URL 256비트 이상 키 |
| `OPENAI_API_KEY` | 선택. 없으면 AI 호출만 503으로 거절 |
| `PUBLIC_DATA_SERVICE_KEY` | 선택. 공공데이터포털의 일반 인증키(Decoding) |

JWT는 세션을 만들지 않는 Bearer 토큰 인증입니다. `JWT_SECRET`은 사용자 토큰이 아니라 서버의 서명키입니다.
키가 없거나 짧으면 시작하지 않습니다. 실제 키나 개인 `.env`는 공유하지 않습니다.

PowerShell에서 개인 키 생성:

```powershell
$keyBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
[Convert]::ToBase64String($keyBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$rng.Dispose()
```

Python을 사용할 수 있으면 `python -c "import secrets; print(secrets.token_urlsafe(32))"`도 가능합니다.
출력값을 개인 `.env`의 `JWT_SECRET`에 넣습니다.

## 2. MySQL 준비

설치된 MySQL을 사용하거나 Docker Compose 중 하나를 선택합니다.
기존 DB의 데이터·계정 비밀번호는 변경하지 않습니다. 테이블 생성 SQL은 기존 테이블을 수정하지 않으므로
기존 스키마가 다른 경우에는 별도로 차이를 확인해야 합니다.

### 설치된 MySQL / Workbench

관리자 연결에서 `sql/00_PILLBADEUM_DB_CREAT.sql`을 실행하고,
`pilldb`를 선택한 연결에서 `sql/01_PILLBADEUM_DB_SCRIPT.sql`을 실행합니다.
`00`은 로컬 개발 계정 `pill`을 준비하며, 이미 있는 계정의 비밀번호는 유지합니다.

PowerShell에서는 두 작업을 한 번에 실행할 수도 있습니다. 관리자 비밀번호는 MySQL이 직접 묻습니다.

```powershell
.\scripts\setup-local-db.ps1
```

클라이언트를 찾지 못하면 `-MySqlClient 'C:\...\mysql.exe'`를 지정합니다.

### Docker Compose

개인 `.env`에 `DB_USERNAME`, `DB_PASSWORD`와 별도의 `MYSQL_ROOT_PASSWORD`를 채운 뒤 실행합니다.
호스트에 이미 MySQL이 있다면 설치된 MySQL을 사용하거나 `MYSQL_PORT`와 `DB_URL`의 포트를 함께 바꿉니다.

```text
docker compose up -d --wait
docker compose exec mysql mysql -u pill -p pilldb -e "source /sql/01_PILLBADEUM_DB_SCRIPT.sql"
```

Compose는 DB를 로컬 주소에만 열고 데이터를 named volume에 보존합니다.
기존 volume의 계정 비밀번호는 `.env`를 바꾸는 것만으로 변경되지 않습니다.

### 선택: 샘플 데이터

테이블만 만든 비어 있는 개발 DB에서 `sql/02_PILLBADEUM_TEST_DATA.sql`을 한 번 실행합니다.
PowerShell 준비 스크립트는 `-IncludeTestData` 옵션을 지원합니다.
샘플 비밀번호 해시는 예시 데이터이며, 실제 로그인 계정을 만드는 API는 아직 없습니다.
서버 시작 시 SQL·샘플·초기화 작업은 자동 실행하지 않습니다.

## 3. 서버 실행

```powershell
.\gradlew.bat bootRun
```

macOS/Linux: `./gradlew bootRun`.

- Health: http://localhost:8080/actuator/health
- 로컬 Swagger: http://localhost:8080/swagger-ui/index.html
- OpenAPI: http://localhost:8080/v3/api-docs
- 프론트 기본 Origin: `http://localhost:5173`. 추가 주소는 `CORS_ALLOWED_ORIGINS`로 지정

로그인 담당자가 JWT 발급 코드를 연결하면 요청에 `Authorization: Bearer <토큰>`을 보냅니다.
로그인·회원가입·약관 경로와 health는 공개하고 다른 경로는 JWT가 필요합니다.
로그아웃은 기존 `TokenBlacklist`에 토큰을 등록하는 방식으로 연결합니다.
정확한 공개 경로는 `common/security/SecurityConfig`에 있습니다.

## 설정 구분

- 기본 프로필은 `local`. `application-local.yaml`에서만 개인 `.env`를 읽고 Swagger를 켭니다.
- 배포는 `SPRING_PROFILES_ACTIVE=prod`와 DB/JWT 환경변수를 명시합니다. `.env`를 읽지 않고 Swagger도 끕니다.
- 운영체제 환경변수는 `.env`보다 우선합니다. 연결 값이 예상과 다르면 실행 터미널의 `DB_*`도 확인합니다.
- DB 테이블은 수동 SQL 관리, `ddl-auto: none`, `open-in-view: false`를 유지합니다.
- JVM·JDBC·Compose의 시간대는 `Asia/Seoul`, 가상 스레드는 활성화합니다.
- `common/exception/GlobalExceptionHandler`와 보안 필터의 오류 응답은 `{code, description, detail}`로 통일합니다.
- 요청 ID는 `X-Request-Id` 응답 헤더와 서버 로그에 남습니다.
- `.env`, IDE·빌드 파일, 업로드·로그는 Git에서 제외합니다. 공유 양식은 `.env.example`입니다.

## 검증

```powershell
.\gradlew.bat test bootJar
```

일반 테스트는 MySQL 없이 JWT 인증·인가, CORS, 요청 ID와 공통 오류 처리를 검증합니다.
실제 MySQL 검증을 포함하려면 **전용 `pill_test` DB**를 만들고 다음을 지정합니다.

```powershell
$env:MYSQL_TEST_URL = 'jdbc:mysql://localhost:3306/pill_test?connectionTimeZone=Asia/Seoul&forceConnectionTimeZoneToSession=true'
$env:MYSQL_TEST_USER = 'pill'
$env:MYSQL_TEST_PASSWORD = '<테스트 DB 비밀번호>'
.\gradlew.bat test bootJar
```

테스트는 DB 이름이 `pill_test`인지 확인한 후에만 스키마·샘플을 적용합니다.
`MYSQL_TEST_URL`이 없으면 실제 DB 검증만 생략됩니다. CI는 MySQL 8.4 임시 DB에서 이 검증도 실행합니다.
Flyway/H2는 사용하지 않습니다. 테스트용 Controller와 서명키는 실행 JAR에 포함되지 않습니다.

[패키지 안내](docs/PACKAGE_GUIDE.md) · [SQL 안내](sql/README.md)
