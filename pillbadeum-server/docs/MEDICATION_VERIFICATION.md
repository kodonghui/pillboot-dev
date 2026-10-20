# 복약지도 백엔드 검증 기록

검증일: 2026-10-19. 대상은 일정·캘린더·복용 예정 조회·Live·알림이다.
복약 완료 체크와 실제 복용 기록은 제외한다.

## 2026-10-20 음성 제외 최종 점검

사용자 요청에 따라 Live·단발 음성 테스트를 전부 제외했다. Java 103개 전부 통과(실제 MySQL 포함, 실패·제외 0), 실제 8080 서버의 읽기 전용 검사 13개 통과, bootJar 통과다.
알림 동의·구독 저장/갱신·계정 이동·끄기 삭제·구독 키 갱신 후 만료 처리·분 단위 중복 방지의 실제 MySQL 통합 테스트 4개를 추가했다.
사용자 DB의 03 적용 완료도 읽기 전용으로 확인했다. 데이터 변경 검증은 임시 MySQL에서만 실행했고 검증 후 종료했다.
범위와 미검증 항목은 [음성 제외 검증 결과](MEDICATION_NONVOICE_VERIFICATION.md)에 정리했다. 실제 브라우저 푸시 수신은 이 결과에 포함하지 않는다.

## 2026-10-20 SQL 통합 검증

- GitHub develop e7c5338의 00~02는 원본으로 유지하고 로컬 추가 변경 03~06을 `sql/03_MEDICATION_UPDATE.sql` 하나로 합쳤다. 기존 개별 03~06 파일은 제거했다.
- 새 DB 실행 순서는 00 → 01 → 02(샘플 선택) → 03이다. 이미 원본 DB를 만들었거나 예전 변경을 일부 적용한 DB는 새 03만 실행한다. 문서·설치 스크립트·테스트 DB 준비 순서도 맞췄다.
- 업무 DB와 분리된 임시 MySQL 8.0.46(127.0.0.1:13307, 작업 폴더 아래 별도 datadir)에서 원본 DB 첫 적용, 예전 03~05 적용 상태, 이미 최신 상태의 세 시나리오를 검사했다. 모두 최초 적용과 재실행이 성공했고 결과 스키마가 통합 전 최신 스키마와 일치했다. 기존 내 약 값의 해시도 유지됐다.
- item_seq=NULL인 직접 입력 약 저장과 그 행을 남긴 재실행도 확인했다. 회원별 알림 설정 중복 시 전체 변경을 BLOCKED로 건너뛰고 원래 스키마를 유지하는 것도 확인했다.
- 같은 임시 서버의 전용 pill_test에서 Gradle `--offline test bootJar` 통과: 전체 401개 중 392개 통과, MySQL+Qdrant 동시 검증 9개만 제외, 실패 0. 기존에 제외됐던 실제 MySQL 일정 저장·조회·수정·중단·삭제와 스키마 제약 검증도 이번에 실행했다.
- 실제 사용자 DB, OpenAI 음성 세션, 푸시 발송은 실행하지 않았다. 아래 이전 검증의 06번은 이번에 통합된 옛 개별 파일을 뜻한다.

## 2026-10-20 develop 병합 검증

- 원격 develop `e7c533857a1c04db2f9b91f4868d9ad28ab7b35f`를 로컬 feat/MG에 병합했다. 원격 push나 PR 생성은 하지 않는다.
- 약 목록 중복 매핑을 제거했다. 팀원의 `/api/profiles/{profileId}/drugs`는 유지하고 일정용 약 선택은 `/api/profiles/{profileId}/drugs/schedule-choices`로 분리했다. 문서·HTTP 예시·검사 스크립트도 맞췄다.
- 프로필 엔티티와 팀원 테스트의 컬럼명을 현재 로컬 SQL의 relationship_type/material에 맞췄다. Drug의 약품 동기화 변경과 성분명 설명은 유지했다.
- 직접 입력 약은 팀원의 내 약 목록에서도 이름으로 표시한다. 품목 코드가 없는 행은 대화 컨텍스트의 공식 약품·DUR 조회 목록에서 제외해 NULL 접근과 잘못된 품목 조회를 막는다. 직접 입력 이름만으로 약품 정보를 추정하지 않는다.
- 공통 OpenAiClient의 확장된 생성자에 맞춰 복약지도 테스트를 수정했다. SchemaContract의 기대값은 125개 컬럼·CHECK 3개로 맞췄다.
- Gradle `--offline test bootJar` 통과: 전체 397개, 통과 340개, 실제 MySQL/Qdrant 전용 57개 제외, 실패 0. 전체 애플리케이션 빈 구성·JPA 저장소 구성·API 매핑과 Swagger HTTP 응답은 DB 대역으로 확인했다.
- Node 브라우저 모의 테스트 18개 통과. 실제 DB 마이그레이션·음성 연결·푸시 발송은 이번 병합 검증에서 실행하지 않았다.

## 2026-10-20 직접 입력 약 지원 추가 검증

- Gradle `--offline test bootJar`: 전체 220개 중 213개 통과, 전용 MySQL 테스트 7개 제외, 실패 0. 실행 파일 빌드 통과.
- Node Live 브라우저 모의 테스트: 18개 통과. 내 약 ID 생략 시 null 전달, 잘못된 ID 차단을 포함한다.
- 새 직접 입력 약 제안·동의·위임·이름 근거·프로필 소유권·실제 발급 ID 반환을 검증했다. OpenAI는 mock이며 실제 음성 연결은 호출하지 않았다.
- 이번 변경의 기존 DB 마이그레이션(06)은 실행하지 않았다. 따라서 아래 이전 검증 결과가 새 custom_name 컬럼이나 실제 NULL 약품 일정의 DB 저장 검증까지 뜻하지 않는다.
- 실제 DB 검증은 06 적용 후 필요하다. 전용 pill_test에서는 직접 입력 약 생성·커밋·조회·수정·그만 먹기·삭제 테스트를 추가했으나 이번 실행에서는 제외됐다.
- 적용과 테스트 방법: [직접 입력 약 일정](MEDICATION_CUSTOM_NAME.md).

## 2026-10-20 테스트 준비·추가 확인

새로 가입한 테스트 계정에 본인·엄마·아빠 프로필과 내 약 6건을 준비했다.
일정 없음·진행 중·이미 종료·시작 전 상태를 한 번에 만드는 [테스트 계정 준비 도구](MEDICATION_TEST_ACCOUNT.md)를 추가했다.
이 계정의 실제 로그인과 저장→조회→중단→내일 제외→삭제→약 행 유지, 시작 전/종료 후 중단 409, 다른 회원 일정 조회 404를 실행해 확인했다.
본인 타이레놀은 검증 후 일정 없는 초기 상태로 복원했다. 실제 DB를 읽는 HTTP 검사 12개도 이 계정으로 모두 통과했다.
이 확인은 업무 DB 안의 명시적인 테스트 프로필·약을 대상으로 한 수동 API 검사다. 아래의 전용 pill_test 자동 테스트 6개 제외와 구분한다.

## 이번 실행 결과

| 항목 | 결과 | 실제 확인 범위 |
| --- | --- | --- |
| Gradle `--offline test bootJar` | 통과 | 전체 209개 중 203 통과, MySQL 전용 6개 제외, 실패 0 |
| Live 브라우저 모의 테스트 | 14개 통과 | 연결·종료·오디오 정리·완료 판정·캘린더 갱신을 가짜 미디어/시계로 검증 |
| 실제 MySQL 연결·컬럼 검사 | 통과 | 프로필 관계·약 이름·일정 nullable 3개·알림/구독/발송 표·시행 중 알림 약관. 읽기 전용 |
| 실행 중인 서버 + 실제 MySQL 조회 | 12개 통과 | 아래 HTTP 검사. 18080 임시 서버에서 푸시 스케줄러를 끄고 실행 |
| OpenAI 모델 정보 조회 | 두 모델 HTTP 200 | `gpt-live-1`, `gpt-4.1-mini` 메타데이터 접근 확인. 실제 대화·생성 성공 증거는 아님 |
| VAPID 공개 키 API | 통과 | 설정된 키 쌍으로 서버 시작, 공개 키 응답·65바이트 형식 확인 |
| 실제 DB 저장·잠금·커밋 통합 테스트 | 미실행 | 현재 계정이 `pill_test`에 접근 불가: SQLState 42000, MySQL 1044 |
| 실제 Live 음성·자동 종료·최종 사용량 수신 | 수동 확인 필요 | 이번 작업에서는 과금 Live 세션이나 생성 요청을 만들지 않음 |
| 실제 브라우저 푸시 수신·클릭 이동 | 수동 확인 필요 | 이번 작업에서는 기존 브라우저 구독 대상으로 푸시를 보내지 않음 |

실제 HTTP 검사 12개:

1. 서버 health UP.
2. 캘린더 미인증 401.
3. 잘못된 JWT 401.
4. 계정 캘린더 응답·기간 포함 검사와 요청 memberId로 계정 변경 불가.
5. 날짜별 복용 예정 목록·시각 형식.
6. 잘못된 날짜·시각 400.
7. 소유한 프로필의 약 목록·일정 상세.
8. 없는 프로필 404.
9. 알림 설정 응답.
10. VAPID 공개 키 준비 상태.
11. 빈 Live 시작 요청 400, 없는 세션 404. 실제 Live 생성 없음.
12. 프론트 Origin의 CORS preflight.

`/v3/api-docs`에서도 실제 실행 계약을 추출했다. [복약지도 OpenAPI JSON](MEDICATION_OPENAPI.json)은 10개 경로·18개 스키마이며 내부 참조 23개를 확인했다.
프론트가 읽을 동작·상태·오류 설명은 [인계 문서](MEDICATION_FRONTEND_HANDOFF.md)에 있다.

## 환경 확인 사항

현재 검사 터미널에 상속된 `DB_PASSWORD`와 프로젝트 `.env`의 값이 달랐다.
Spring은 환경변수를 우선하므로 이 조합의 접속은 인증 오류(SQLState 28000)가 났다.
검사 자식 프로세스에서만 상속 값을 제외하고 프로젝트 `.env`로 접속했을 때 성공했다.
사용자 환경변수·개인 `.env`·기존 일정을 변경하지 않았다.
같은 오류가 나면 IDE 실행 환경과 `.env`의 적용 우선순위를 확인한다. 비밀번호를 로그에 출력하지 않는다.

## 다시 실행하기

명령 기준 폴더는 `pillbadeum-server`다.

### 자동 테스트

```powershell
.\gradlew.bat --offline test bootJar --console=plain
node scripts/test-medication-live.cjs
```

기본 실행은 외부 유료 API를 호출하지 않는다. `MYSQL_TEST_URL`이 지정되어 있으면 MySQL 전용 테스트도 켜진다.
쓰기 검증 전에는 URL이 **전용 pill_test**인지 확인한다. 업무 DB URL을 넣지 않는다.

### 실제 DB 읽기 전용 검사

Java 21과 기존 Gradle 캐시의 MySQL JDBC 드라이버를 사용한다. mysql.exe 설치는 필요 없다.

```powershell
$driverFile = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j" -Filter '*.jar' -Recurse |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Sort-Object FullName -Descending | Select-Object -First 1
java --class-path $driverFile.FullName scripts/CheckMedicationDb.java
```

`DB_URL/DB_USERNAME/DB_PASSWORD`는 환경변수 우선, 없으면 `.env`를 읽는다.
접속 URL·회원 정보·비밀번호·일정 원문은 출력하지 않는다. DB 생성·테이블 변경·데이터 변경도 하지 않는다.
`PASS dedicated-test-db-connection`은 테스트 DB 접속 확인일 뿐, 쓰기 테스트 통과를 의미하지 않는다.
`SKIP dedicated-test-db-unavailable`은 업무 DB 검사와 구분해서 본다.

### 실행 중인 서버 HTTP 검사

먼저 서버를 실행한다. 아래 스크립트는 일정 변경·Live 생성·푸시 전송을 하지 않는다.
다만 선택한 회원의 일정 등 실제 응답을 메모리에서 검사하므로 해당 회원의 테스트 JWT를 사용한다.

```powershell
$env:MEDICATION_BASE_URL = 'http://localhost:8080'
$env:MEDICATION_ACCESS_TOKEN = '<로그인으로 받은 토큰>'
$env:MEDICATION_PROFILE_ID = '<이 회원의 프로필 ID>'
$env:MEDICATION_MY_DRUG_ID = '<그 프로필의 내 약 ID>'
node scripts/check-medication-api.cjs
```

프로필·내 약 ID를 생략하면 오늘 캘린더의 첫 일정을 사용한다. 오늘 일정이 없으면 두 ID를 직접 지정한다.
인증 토큰이 없으면 인증 후 검사는 SKIP이다. SKIP을 포함한 결과를 전체 통과라고 보고하지 않는다.
다른 프론트 주소는 `MEDICATION_FRONTEND_ORIGIN`으로 지정하고 서버 CORS 허용 Origin도 맞춘다.
검사가 끝나면 터미널에 넣은 `MEDICATION_ACCESS_TOKEN`은 제거한다. 토큰은 파일에 저장하지 않는다.

### 전용 DB 쓰기 통합 검사

관리자가 [README의 pill_test 준비](../README.md#검증)를 참고해 실제 접속 계정·host에 권한을 준 뒤,
`MYSQL_TEST_URL`, `MYSQL_TEST_USER`, `MYSQL_TEST_PASSWORD`를 설정한다. 권한 설정은 이번 작업에서 실행하지 않았다.

```powershell
.\gradlew.bat --offline test --tests '*MedicationScheduleFlowMySqlTests' --tests '*MedicationCalendarMySqlTests' --console=plain
```

새 `MedicationScheduleFlowMySqlTests`는 테스트 시작 전 `SELECT DATABASE()`로 pill_test인지 검사한다.
UUID를 사용한 독립 회원·프로필·약 fixture를 만든 뒤 HTTP → JWT → 서비스 → 실제 커밋 → 별도 JDBC 조회를 확인하고 자기 fixture만 지운다.
저장/수정·중단·삭제·재등록, 소유권/잘못된 기간 거절, 알림 대상 조회 반영을 검증한다. 실제 푸시는 보내지 않는다.
스키마는 `01`의 IF NOT EXISTS를 사용하므로 과거 pill_test 스키마가 다르면 별도로 맞춰야 한다. 기존 표를 자동 업그레이드하지 않는다.

### OpenAPI 다시 추출

local 프로필에서 Swagger가 켜진 서버를 실행한 뒤:

```powershell
node scripts/export-medication-openapi.cjs
```

기본 8080이며 `MEDICATION_BASE_URL`로 바꿀 수 있다. 공개 스키마만 읽고 `docs/MEDICATION_OPENAPI.json`을 갱신한다.
사용자 데이터나 키를 파일에 넣지 않는다. 이 파일은 추출 시점의 계약이므로 API 변경 후 재생성한다.

## 남은 수동 확인

| 검사 | 입력/행동 | 확인 |
| --- | --- | --- |
| 기본 제안 | “오늘부터 이틀 타이레놀” | 제안 동의 전 DB 미변경, “응” 후 저장·캘린더 반영 |
| 위임 | “알아서 저장해” | 선택 날짜·약과 기본값 적용 후 저장 |
| 정정 | 제안 후 “저녁에만” | 기존 초안 교체 후 다시 제안, 17:00 한 시각 적용 |
| 모호한 삭제 | 같은 약이 여러 프로필/함량에 있는 상태에서 요청 | 후보 질문 뒤 지정 대상만 삭제 |
| 일정 삭제 | 테스트용 약 일정 삭제 요청 | 약 행 유지, 기간/시각 NULL, 과거 포함 일정 표시 제거 |
| 그만 먹기 | 진행 중 테스트 일정에 요청 | 종료일 KST 오늘, 과거/오늘 유지, 내일 제외 |
| 연결 종료 | 완료·60초·수동 종료·페이지 이탈 | 마이크와 미디어 정리, 추가 업무 실행 차단, 이미 저장한 일정 유지 |
| 사용량 | 실제 대화 종료 | mini 토큰 로그와 Live 사용 초 로그 분리, 최종 이벤트 누락 시 미확정 표시 |
| 알림 | 본인 브라우저 구독·동의, 다음 분에 해당하는 테스트 일정 | 정시 수신, 같은 시각 중복 없음, 클릭 시 `/doses` 이동 |
| 알림 끄기 | 설정 끄기 후 다음 복용 시각 | 발송 대상 제외, 해당 회원의 구독 삭제 |

실제 Live는 마이크·오디오 재생과 OpenAI 세션이 필요하며, 실제 푸시는 본인 브라우저의 구독·서비스 워커가 필요하다.
따라서 자동 테스트·모델 메타데이터 응답·공개 키 조회만으로 실제 음성/푸시 수신까지 검증했다고 표시하지 않는다.
OpenAI 모델 메타데이터 조회 의미는 [공식 모델 조회 API](https://developers.openai.com/api/reference/resources/models/methods/retrieve)를 참고한다.
