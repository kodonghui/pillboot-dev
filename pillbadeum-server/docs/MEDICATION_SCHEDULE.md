# 복약 일정 관리 기본 뼈대

실시간 대화형 음성 등록·일정 삭제·그만 먹기는 [GPT-Live 안내](MEDICATION_LIVE.md)를 따릅니다.
아래 단발 음성 API와 달리, Live는 빠진 항목을 기본 설정으로 채운 일정을 제안하고 동의 후 저장합니다. 사용자가 맡기면 바로 기본값을 적용합니다.

구현 범위는 일정 등록용 약 목록, 일정 상세 조회, 저장·수정, 그만 먹기·삭제, 계정 캘린더·날짜별 복용 예정 조회와 음성 일정 관리 API입니다.
복약 완료 체크·취소, 미복용 상태와 실제 복용 기록은 구현 범위에서 제외합니다. doses는 복용 예정 목록입니다.
Live 로컬 참고 화면과 푸시 발송 백엔드도 구현되어 있습니다. 정식 프론트 연결은 [프론트 인계 문서](MEDICATION_FRONTEND_HANDOFF.md), 실제 검증 범위는 [검증 기록](MEDICATION_VERIFICATION.md)을 참고하세요.

## 요청이 지나가는 순서

```text
Bearer JWT → MedicationScheduleController → MedicationScheduleService
                                        ├─ MedicationRepository (회원·프로필·약을 함께 거르는 조회)
                                        └─ MedicationScheduleWriteRepository (행 잠금)
                                             → 기존 drug.domain.MyDrug 변경
                                             → 트랜잭션 커밋 시 DB UPDATE

Bearer JWT → MedicationCalendarController → MedicationCalendarService
                                        └─ MedicationRepository.calendar (회원 전체 프로필·기간 필터)
```

- controller: HTTP 주소와 입력 DTO를 받고, 토큰의 회원 ID를 서비스에 전달합니다.
- dto: 요청 필드와 응답 모양입니다. PUT은 전체 교체이므로 timing을 생략하면 기존 값도 지웁니다.
- service: 소유권, 트랜잭션, 그만 먹기의 날짜 처리 순서를 담당합니다.
- domain: 기존 MyDrug의 기간·시간 검사와 변경 로직을 사용합니다. 내 약 엔티티를 중복 생성하지 않습니다.
- 조회 응답은 MyDrug 변경 값을 직접 조립합니다. JPA 변경 직후 JDBC로 읽어 이전 값이 응답되는 일을 피합니다.

## API

기본 주소: `/api/profiles/{profileId}/drugs/{myDrugId}/schedule`

| 요청 | 현재 동작 |
| --- | --- |
| GET | 소유권을 포함해 일정 조회, 200. 등록된 일정이 없으면 404 |
| PUT | 일정 등록 또는 기존 일정 전체 교체, 200 |
| POST .../stop | 진행 중 일정의 종료일을 한국 시간 오늘로, 200 |
| DELETE | 선택한 내 약의 일정만 삭제, 204. 일정이 없으면 404 |

다른 회원의 데이터, 다른 프로필의 약, 없는 약은 모두 404입니다.
인증이 없으면 기존 보안 필터가 401을 반환합니다.
PUT에서 1회 개수는 1~10, 복용 때는 최대 20자, 날짜·복용 시간은 필수입니다.
기간 순서, 시간 1~3개·중복 검사는 기존 MyDrug가 담당합니다. 초는 기존 MyDrug 정책대로 분 단위로 정리합니다.
동시 일정 수정은 내 약 행을 잠근 뒤 처리합니다. 같은 날짜에 그만 먹기를 반복해도 종료일은 같습니다.

## 일정 등록용 약 목록 조회

`GET /api/profiles/{profileId}/drugs/schedule-choices`

- 토큰 회원이 소유한 프로필의 내 약을 myDrugId 순서로 반환합니다. 현재 별도 내 약 목록 API가 없어 일정 화면용 조회 경로를 연결했습니다.
- 일정이 있는 약과 없는 약을 모두 포함합니다. 일정이 없으면 hasSchedule=false, 기간은 NULL, doseTimes는 빈 목록입니다.
- 내 프로필에 약이 없으면 200과 `{"drugs":[]}`입니다. 없는 프로필이나 다른 회원의 프로필은 동일하게 404입니다.
- 목록에서 선택한 myDrugId와 현재 profileId를 기존 일정 PUT에 사용합니다. 새 약이나 새 일정을 자동 생성하지 않습니다.
- 현재 일정 정보를 함께 제공하므로 이미 일정이 있는 약은 수정 화면으로, 일정 없는 약은 등록 화면으로 연결할 수 있습니다.
- 약 담당자가 같은 주소를 구현할 때는 경로를 중복 선언하지 않고 이 조회 계약을 통합합니다. 테이블·인증 설정 변경은 없습니다.

5번 회원의 예시 프로필로 `GET /api/profiles/13/drugs/schedule-choices`를 호출하면 프로필 13의 약 목록을 확인할 수 있습니다.

## 계정 전체 캘린더 조회

`GET /api/calendar?from=2026-10-01&to=2026-10-31`

- 토큰의 회원 ID를 기준으로 그 회원이 가진 모든 프로필의 일정을 반환합니다. 회원 ID를 쿼리로 받지 않습니다.
- from·to는 YYYY-MM-DD 형식의 필수 날짜입니다. 누락·잘못된 날짜·시작일이 종료일보다 늦은 요청은 400입니다.
- 조회 범위와 일정 기간이 하루라도 겹치면 포함합니다. 시작일·종료일 양 끝을 포함하며 같은 날 조회도 가능합니다.
- 삭제한 일정, 조회 기간 밖 일정, 다른 회원의 일정은 제외합니다. 일정이 없는 계정·기간은 200과 빈 schedules 목록입니다.
- profileId·myDrugId 순으로 약당 한 건씩 반환합니다. 여러 날·하루 여러 번인 일정도 날짜·시각별로 복제하지 않습니다.
- 각 일정의 startDate·endDate는 실제 저장 기간입니다. 화면은 조회 범위와 겹치는 날짜에 doseTimes의 시각들을 표시합니다.
- 응답의 profileId·profileName으로 프로필을 구분합니다. 현재 DB에는 색상 컬럼이 없으므로 화면에서 profileId에 색상을 매핑해야 합니다.
  색상 선택·저장이나 캘린더 화면 자체는 이번 API 구현에 포함하지 않습니다.
- 테이블·컬럼 변경은 없습니다. 기존에 일정 삭제용 03을 적용했다면 추가 SQL이 필요하지 않습니다.

응답 예시(일정이 없는 경우):

```json
{
  "from": "2026-10-01",
  "to": "2026-10-31",
  "schedules": []
}
```

일정이 있으면 schedules에 기존 일정 상세와 같은 필드가 들어갑니다.
Swagger에서 기존 JWT로 인증한 뒤 from=2026-10-01, to=2026-10-31을 넣어 확인합니다.
선택한 일정의 DELETE 후 다시 조회하면 해당 myDrugId가 빠지고, PUT으로 재등록하면 다시 나타납니다.

## 날짜·시간별 복용 예정 목록

`GET /api/calendar/doses?date=2026-10-19&time=07:00`

- date는 YYYY-MM-DD 형식의 필수 날짜이고, time은 HH:mm 형식의 선택 값입니다. time을 생략하면 하루 전체입니다.
- 토큰 회원의 모든 프로필에서 해당 날짜에 포함되는 일정의 복용 시각을 펼칩니다. 한 약을 하루 세 번 먹으면 하루 조회에 세 항목이 나옵니다.
- 시각·profileId·myDrugId 순으로 반환하며 프로필명·약 이름·1회 개수·복용 때를 함께 제공합니다.
- 삭제한 일정과 기간 밖 일정은 제외하고 종료일은 포함합니다. 해당 날짜·시각에 약이 없으면 200과 빈 doses 목록입니다.
- 계정 캘린더의 schedules는 약별 일정이고, 이 doses는 날짜별 복용 예정 목록입니다. 복약 완료·미복용 상태를 저장하는 기능은 아닙니다.
- 조회 전용 API이며 추가 SQL이나 알림 담당 코드 변경은 없습니다.

## 일정 삭제와 재등록

한 계정의 캘린더에서 여러 프로필의 일정을 표시하더라도, 삭제 대상은 경로로 지정한 프로필의 내 약 한 건입니다.
현재 모델은 `my_drug` 한 행에 일정 하나를 저장합니다. 개별 날짜 한 칸만 지우는 기능은 포함하지 않습니다.

DELETE는 해당 행의 `start_date/end_date/dose_times`만 NULL로 바꿉니다.
약 행·프로필·1회 개수·복용 때·등록 경로와 다른 약의 일정은 유지합니다. 감사 시각 updated_at은 갱신됩니다.
세 값은 모두 NULL(일정 없음)이거나 모두 값이 있어야 하며, 엔티티와 `chk_my_drug_schedule_presence` CHECK에서 검사합니다.
캘린더 API는 일정 없는 약을 제외하고, 내 약 목록용 쿼리는 포함합니다. 캘린더 화면 연결은 후속 작업입니다.
응답의 `hasSchedule`은 이 세 값으로 계산하는 표시이며 DB에 컬럼을 추가하지 않습니다.
일정 없는 약은 내 약 목록에서 기간 NULL·복용 시간 빈 목록으로 표현할 수 있습니다.
일정 상세 GET과 그만 먹기·삭제는 일정이 없으면 404입니다. 삭제 후 같은 주소에 PUT하면 같은 약에 일정을 다시 등록하고 200을 반환합니다.

신규 DB는 기존 `sql/01_PILLBADEUM_DB_SCRIPT.sql` → 02(샘플 선택) → `sql/03_MEDICATION_UPDATE.sql` 순서로 준비합니다.
기존 14개 테이블 DB에는 대상 DB를 확인한 뒤 `sql/03_MEDICATION_UPDATE.sql`을 별도로 한 번 적용해야 합니다.
01의 IF NOT EXISTS는 기존 컬럼을 바꾸지 않습니다. 변경 전 DB에서는 일정 삭제 시 NOT NULL 제약에 실패합니다.
통합 03은 기존 일정 값을 보존하고 일정 NULL 허용·직접 입력 이름·알림 표·컬럼명 오타 변경을 함께 적용합니다. 이미 적용된 항목은 건너뛰므로 새 DB와 기존 DB 모두 사용합니다.
DB의 dose_times 기본값은 NULL이고, 약을 기간과 함께 등록할 때 시각을 생략하면 MyDrug가 07:00·11:00·17:00을 채웁니다.
일정 PUT에는 날짜·시각이 필수이므로 화면에서 기본값을 채워 보냅니다.

## 그만 먹기

시작 전 그만 먹기도 종료일을 오늘로 줄이면 기간이 역전되므로 409입니다.
시작 전 일정을 취소하려면 일정 삭제를 사용합니다.
종료일이 이미 지난 일정 역시 409이며, 종료일이 오늘인 일정은 그대로 성공합니다.
진행 중 일정은 그만 먹기를 눌러도 오늘 남은 시각까지 포함되고 내일부터 제외됩니다.

아직 합의되지 않은 최대 기간, 시작일 수정 제한 등은 임의로 추가하지 않았습니다.

## 음성 문장으로 일정 저장

`POST /api/schedule-voice-commands`

```json
{
  "text": "일주일 동안 삼시세끼 약 일정 추가해줘",
  "selectedProfileId": 13,
  "selectedMyDrugId": 25,
  "selectedDate": "2026-11-03"
}
```

브라우저 STT의 최종 문장과 현재 선택한 프로필·약·클릭 날짜를 전송합니다.
사용자 확인 버튼 없이 기존 일정 저장 서비스를 호출하며, 같은 약에 이미 일정이 있으면 PUT과 같이 전체 교체합니다.
원본 오디오는 서버로 전송하지 않습니다. 브라우저 음성 인식, 화면 자동 갱신, 반환된 MP3 재생은 프론트에서 연결해야 합니다.

| 생략 항목 | 적용 값 |
| --- | --- |
| 프로필·약 | 화면에서 선택한 약. 선택한 프로필·약 조합도 토큰 회원 소유인지 검사 |
| 시작일 | 캘린더에서 클릭해 보낸 selectedDate |
| 종료일·기간 모두 | 시작일 당일, 하루 일정 |
| 종료일 대신 기간만 | 시작일 + 기간 - 1일. 일주일은 7일, 양 끝 포함 |
| 복용 횟수·시각 모두 | 07:00·11:00·17:00 |
| 횟수만 | 위 기본 시각에서 앞의 N개 사용. 하루 2회면 07:00·11:00 |
| 1회 개수·복용 때 | 대상 약에 이미 저장된 dosePerTime·timing |

- “오늘부터”, “내일부터”는 한국 시간의 오늘을 기준으로 해석하며 클릭 날짜보다 우선합니다.
- “아침·저녁”은 미리 정해 둔 화면 시각 07:00·17:00으로 변환할 수 있습니다. 임의 복용법을 추천하는 기능이 아닙니다.
- AI에게는 로그인 회원의 약 후보만 제공합니다. 명시된 프로필·약을 찾지 못하거나 모호하면 400으로 거절합니다.
  다른 프로필만 말한 경우 그 프로필에서 현재 선택 약과 같은 이름의 약이 유일할 때만 대상으로 고릅니다.
- 서버는 AI가 반환한 약 ID도 회원 소유 후보인지 재검사합니다. 임의·다른 회원의 약 ID는 저장할 수 없습니다.
- 시각 1~3개·중복 없음·1회 개수 1~10·기간 순서 등은 서버와 기존 MyDrug 규칙으로 검사합니다.
- 삭제·그만 먹기 명령이나 일정과 관련 없는 문장은 저장하지 않습니다. 해석 실패·잘못된 값도 기본값으로 덮어 성공 처리하지 않습니다.
- 외부 해석 호출 중에는 DB 행을 잠그지 않습니다. 저장 서비스에서 소유권을 다시 확인하고 트랜잭션을 커밋한 뒤 완료 음성을 생성합니다.
- 응답은 schedule, message, appliedDefaults, audioStatus, audioBase64, audioContentType, aiGeneratedVoice입니다.
  appliedDefaults는 서버가 채운 항목 이름입니다. 예시 명령은 11월 3~9일의 하루 세 번 일정이 됩니다.
- TTS가 성공하면 audioStatus=READY와 Base64 MP3를 반환합니다. 생성 음성임을 화면에 표시해야 합니다.
- 일정 저장은 성공했지만 TTS만 실패·미설정이면 200과 audioStatus=UNAVAILABLE, 텍스트 완료 문구를 반환합니다.
  이 경우 저장 요청을 다시 보내지 않습니다. 해석·저장이 실패하면 완료 음성도 생성하지 않습니다.
- 새 테이블·컬럼을 추가하지 않습니다. 알림 담당 코드·보안 설정은 변경하지 않습니다.

설정은 로컬 .env에 OPENAI_API_KEY, OPENAI_CHAT_MODEL, OPENAI_TTS_MODEL을 지정합니다.
OPENAI_CHAT_MODEL은 Responses API의 Structured Outputs를 지원하는 계정 사용 가능 모델이어야 합니다.
OPENAI_TTS_MODEL은 Speech API 모델이며 예시는 gpt-4o-mini-tts입니다. 음색은 OPENAI_TTS_VOICE, 기본 coral입니다.
키·해석 모델이 없으면 저장 전에 503입니다. TTS 모델만 없으면 저장은 가능하고 완료 음성만 생략합니다.
실제 키나 유료 API를 사용한 검증은 수행하지 않았습니다.

브라우저 연동 순서: STT 최종 문장 1회 수신 → 선택값과 POST → 200이면 캘린더 재조회 → READY이면 MP3 재생.
중간 인식 결과마다 저장하지 않으며, 요청 실패·시간 초과 시 무조건 자동 재시도하지 않습니다.

공식 API 근거: [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses),
[Text to speech](https://developers.openai.com/api/docs/guides/text-to-speech),
[Create speech](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create).

## 확인

`requests/medication-schedule.http`에 요청 예시가 있습니다. 실제 회원·프로필·내 약과 정상 Bearer 토큰이 필요합니다.
기본 테스트는 실제 MyDrug와 서비스, JWT 필터와 MVC 입력 검증을 사용하며 DB 접근은 mock으로 분리합니다.
선택 실행하는 MedicationCalendarMySqlTests는 전용 pill_test에서 실제 조회 SQL의 계정 분리·기간 경계·일정 삭제 제외를 검사합니다.
fixture는 롤백하며, MYSQL_TEST_URL이 없으면 이 검증을 생략합니다.
음성 테스트는 로컬 HTTP fixture·mock으로 구조화 출력 요청, 기본값, 소유권, 검증 실패, MP3 응답과 TTS 실패 처리를 확인합니다.
실제 MySQL에서 쿼리·JPA 잠금·커밋이 동작하는지 확인하는 통합 검증은 별도로 필요합니다.
MySQL 스키마 테스트를 켤 때는 전용 pill_test DB도 원본 01과 통합 03으로 준비합니다. 테스트 준비 도구가 이 순서를 적용합니다.
테스트가 실행하는 CREATE TABLE IF NOT EXISTS만으로는 기존 pill_test 스키마가 갱신되지 않습니다.

```powershell
.\gradlew.bat test bootJar
```
