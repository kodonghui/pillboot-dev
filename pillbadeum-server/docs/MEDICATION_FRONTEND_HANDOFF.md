# 복약지도 프론트 연결 계약

이 문서는 현재 컨트롤러·DTO와 일치하는 프론트 인계 기준이다. 상세 동작은
[일정](MEDICATION_SCHEDULE.md), [Live](MEDICATION_LIVE.md), [알림](MEDICATION_NOTIFICATION.md)을 참고한다.
도구에 가져올 수 있는 [OpenAPI JSON](MEDICATION_OPENAPI.json)과 [Live HTTP 요청 예시](../requests/medication-live.http)도 함께 전달한다.

## 범위와 담당

복약지도는 복용 **예정 일정과 알림**을 관리한다. 먹었음·안 먹었음, 복약 완료 체크·취소와 실제 복용 기록은 구현하지 않는다.
`/api/calendar/doses`도 복용 예정 목록이며 복용 완료 상태가 아니다.

| 백엔드 | 프론트 |
| --- | --- |
| 소유권 검사, 일정 저장·수정·삭제·중단, 일정/복용 예정 조회 | 캘린더, 날짜 선택, 입력 폼, 프로필별 색상 표시 |
| Live 세션 생성·상태·종료, 해석, DB 반영, API 키 보관 | 마이크 권한, WebRTC·오디오 재생, 자막, 완료 후 화면 갱신 |
| 알림 동의·설정, 구독 저장, 정시 발송 | 브라우저 알림 권한·구독, 서비스 워커, 알림 클릭 이동 |

정식 프론트 프로젝트는 이 저장소에 포함되어 있지 않다. `/dev/medication-live`는 local 프로필에서만 열리는 연동 참고 화면이다.
프로필 선택 목록은 프로필 담당 파트와 연결한다. 이 파트에서 별도의 `GET /api/profiles`를 제공한다고 가정하지 않는다.

## 공통 규칙

- 업무 API는 `Authorization: Bearer <accessToken>`을 보낸다. 쿠키 인증이 아니다.
- 회원 ID는 JWT에서 결정한다. 요청에 `memberId`를 추가해 계정을 바꿀 수 없다.
- 로컬 API 기본 주소는 `http://localhost:8080`, 허용 Origin 기본값은 `http://localhost:5173`이다.
- 날짜는 `YYYY-MM-DD`, 시각은 `HH:mm`, 업무 날짜 기준은 한국 시간이다. Live `expiresAt`은 UTC ISO Instant이므로 `Date.parse`로 처리한다.
- 정상 응답은 아래 DTO 자체이며 `{data: ...}` 래퍼가 없다. 204 응답은 `response.json()`을 호출하지 않는다.
- 오류는 `{ "code": "NOT_FOUND", "message": "..." }` 형태다. 오류 메시지·빈 목록을 성공으로 바꾸지 않는다.
- 변경 요청을 자동으로 반복하지 않는다. 실패·시간 초과 시 조회로 현재 상태를 확인한 뒤 사용자가 재시도한다.

| HTTP | 주요 code | 처리 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 날짜·기간·시각·개수 입력 확인 |
| 401 | `UNAUTHENTICATED` | 토큰 누락·만료 확인, 재로그인 |
| 404 | `NOT_FOUND` | 없는 대상과 다른 회원의 대상은 동일하게 숨김 |
| 409 | `CONFLICT` | 시작 전/이미 종료된 일정 중단, 중복 Live 연결 등 |
| 409 | `CONSENT_REQUIRED` | 최신 알림 약관 동의 후 재요청 |
| 503 | `EXTERNAL_API_FAILED`, `EXTERNAL_TIMEOUT` | AI·푸시 설정 또는 외부 연결 확인 |

## 일정과 캘린더

`S` = `/api/profiles/{profileId}/drugs/{myDrugId}/schedule`

2026-10-20 develop 병합 후 내 약 목록 `GET /api/profiles/{profileId}/drugs`는 약 담당의 배열 응답을 유지한다. 일정 등록용 `{drugs: Schedule[]}` 응답은 아래 `schedule-choices` 주소를 사용한다. 일정 CRUD와 Live 주소는 그대로다.

| 메서드·경로 | 응답 | 연결 위치 |
| --- | --- | --- |
| `GET /api/profiles/{profileId}/drugs/schedule-choices` | 200 `{drugs: Schedule[]}` | 현재 프로필의 등록된 약 고르기. 일정 없는 약도 포함 |
| `GET S` | 200 `Schedule`, 일정 없으면 404 | 일정 상세 |
| `PUT S` | 200 `Schedule` | 일정 등록 또는 전체 교체 |
| `POST S/stop` | 200 `Schedule` | 오늘까지만 먹기 |
| `DELETE S` | 204 | 해당 약의 일정 전체 삭제 |
| `GET /api/calendar?from=...&to=...` | 200 `{from,to,schedules: Schedule[]}` | 계정의 모든 프로필 일정 |
| `GET /api/calendar/doses?date=...&time=07:00` | 200 `{date,time,doses: Dose[]}` | 선택 날짜·시각의 복용 예정 목록. time 생략 시 하루 전체 |

`PUT S` 예시:

```json
{
  "startDate": "2026-10-19",
  "endDate": "2026-10-25",
  "doseTimes": ["07:00", "11:00", "17:00"],
  "dosePerTime": 1,
  "timing": "식후 30분"
}
```

시작일·종료일은 필수이며 양 끝을 포함한다. 시각은 중복 없는 1~3개, 1회 개수는 1~10이다.
`timing`은 선택·최대 20자이며 생략하거나 null이면 기존 복용 때도 지운다. PATCH처럼 일부 필드만 보내지 않는다.

`Schedule` 예시(약 ID와 이름은 설명용):

```json
{
  "myDrugId": 25,
  "profileId": 13,
  "profileName": "본인",
  "drugName": "등록된 약 이름",
  "startDate": "2026-10-19",
  "endDate": "2026-10-25",
  "doseTimes": ["07:00", "11:00", "17:00"],
  "dosePerTime": 1,
  "timing": "식후 30분",
  "pushEnabled": false,
  "hasSchedule": true
}
```

- 일정 없는 약은 `hasSchedule=false`, 기간 null, `doseTimes=[]`이다. 캘린더에는 제외된다.
- 한 계정의 캘린더에 여러 프로필 일정을 함께 표시한다. 계정 캘린더 응답을 현재 선택 프로필로 임의 필터링하지 않는다.
- 서버는 약마다 일정 한 건을 보낸다. 프론트가 조회 범위와 일정 기간이 겹치는 날짜에 표시한다.
- 색상 필드·색상 저장 API는 없다. 프론트가 `profileId`별로 일정한 색상을 매핑하고 프로필명도 표시한다.
- 현재 약 한 행에는 일정 하나다. 같은 약의 PUT은 기존 일정을 교체하며 별개의 과거 일정 버전을 만들지 않는다.
- 삭제는 그 약의 일정 전체를 지운다. 특정 날짜 한 칸만 지우는 API가 아니다. 약 자체와 다른 약의 일정은 남는다.
- 중단은 종료일을 한국 시간 오늘로 변경한다. 과거·오늘의 예정 표시는 남고 내일부터 제외된다. 오늘 남은 시각도 포함한다.
- 시작 전·이미 종료된 일정의 중단은 409다. 시작 전 취소는 삭제를 사용한다.
- 변경 성공 후 화면에 현재 보이는 기간의 캘린더와 선택 날짜의 복용 예정 목록을 다시 조회한다.
- `pushEnabled`는 해당 일정만의 설정이 아니라 회원 전체 알림 설정이다.

`Dose` 필드: `myDrugId, profileId, profileName, drugName, time, dosePerTime, timing`.
시각 → 프로필 ID → 내 약 ID 순으로 정렬한다. `completed` 같은 복용 여부 필드는 없다.

## Live 연결 순서

`L` = `/api/medication/live/sessions`

1. 사용자가 선택한 프로필·달력 날짜를 확보한다. 내 약 선택은 선택 사항이며, 등록된 약이 없는 프로필도 Live를 시작할 수 있다.
2. 사용자 버튼 동작에서 마이크 권한을 받고 `RTCPeerConnection`에 오디오 트랙을 추가한다.
3. offer 생성 전에 `oai-events` data channel을 만든다. 로컬 SDP를 설정하고 ICE 수집을 완료한다.
4. `POST L`에 아래 본문과 Bearer JWT를 보낸다.
5. 201 응답의 `sdp`를 answer로 `setRemoteDescription`하고, 원격 오디오 트랙을 재생한다.
6. `GET L/{sessionId}`로 상태를 주기적으로 조회한다. 참고 화면의 조회 주기는 1.5초다.
7. 완료 결과를 확인하면 캘린더·복용 예정 목록을 재조회한다. 완료 안내 뒤 `DELETE L/{sessionId}`로 종료하고 미디어를 정리한다.

```json
{
  "sdp": "브라우저가 만든 offer SDP",
  "selectedProfileId": 13,
  "selectedMyDrugId": 25,
  "selectedDate": "2026-10-19"
}
```

생성 응답: `{ "sessionId": "앱 세션 ID", "sdp": "answer SDP", "expiresAt": "2026-10-19T03:01:00Z" }`.
`selectedMyDrugId`는 생략 또는 `null`을 허용한다. 약 이름도 선택한 약도 없으면 이름을 물으며, “혈압약”처럼 말한 이름으로 등록할 수 있다. 새 약은 저장 확정 시 `my_drug.item_seq=NULL`, `custom_name=발화 이름`, `source=MANUAL`로 생성되고 실제 발급 ID가 완료 응답에 담긴다. 기존 DB는 `sql/03_MEDICATION_UPDATE.sql` 적용이 필요하다.
OpenAI 원격 ID나 API 키를 프론트에서 관리하지 않는다. 브라우저는 우리 서버만으로 세션 생성·조회·종료를 요청한다.
SDP에 임의 문자열을 넣는 HTTP 요청으로는 실제 음성 연결을 테스트할 수 없다.

| 상태/이벤트 | 의미·처리 |
| --- | --- |
| `LISTENING` | 사용자 발화 대기 |
| `PROCESSING` | 해석 또는 업무 처리 중 |
| `AWAITING_CONFIRMATION` | 서버에 제안 초안이 있고 동의를 기다림. 아직 저장 안 됨 |
| `WAITING_FOR_DETAILS` | 추가 정보·정정·오류 안내. 저장 성공으로 취급하지 않음 |
| `SAVED`, `DELETED`, `STOPPED` | 업무 완료. `completedOperation`으로 실제 대상·종류 확인 |
| `session.input_transcript.delta` | 사용자 자막 조각. 그 자체로 DB 저장을 실행하지 않음 |
| `session.output_transcript.delta` | AI 자막 조각. 완료 멘트만 보고 저장 성공으로 판단하지 않음 |
| `session.closed`, 조회/종료의 404 | 세션 종료·만료 가능. 대화 종료로 이미 커밋된 일정이 취소되지는 않음 |

완료된 삭제의 상태 응답 예시:

```json
{
  "sessionId": "앱 세션 ID",
  "status": "DELETED",
  "message": "일정을 삭제했어요.",
  "missingFields": [],
  "appliedDefaults": [],
  "savedSchedule": null,
  "expiresAt": "2026-10-19T03:01:00Z",
  "completedOperation": { "action": "DELETE", "profileId": 13, "myDrugId": 25 }
}
```

등록·중단은 `savedSchedule`에 결과 일정도 들어온다. **삭제는 savedSchedule이 null이어도 성공**이다.
`completedOperation`의 action은 `SAVE / DELETE / STOP`이며, 말로 다른 프로필을 지정할 수 있으므로 처음 선택 ID만으로 화면을 갱신하지 않는다.

대화 제한은 연결 준비를 포함해 최대 60초다. 서버는 업무 성공 후 최대 8초 뒤에도 자동 정리한다.
프론트는 완료 음성 뒤 종료하며, 자막 수신이 잠깐 멈춘 것을 오디오 재생 완료로 단정하지 않는다.
참고 JS의 실제 오디오 활동·완료 자막·무음 확인 또는 유예 종료를 이식할 수 있다.
오류·사용자 종료·페이지 이탈 때도 모든 마이크 트랙, peer, data channel, 폴링·타이머, 오디오 자원을 정리한다.
오래된 연결 요청이 늦게 성공하면 해당 세션을 종료하고 새 세션 상태를 덮어쓰지 않는다.

대화 기본값·동의·삭제 대상 해석은 백엔드가 담당한다. 프론트에서 음성 자막을 다시 해석해 일정 PUT/DELETE를 중복 호출하지 않는다.
Live 음성은 별도 Speech/TTS API를 프론트에서 호출하지 않는다. 기존 단발 `/api/schedule-voice-commands`는 다른 경로이며 Live 연결에 필요하지 않다.

참고 구현: `src/main/resources/medication-live/live.js`.

## 웹 푸시 연결 순서

| 순서 | 작업 |
| --- | --- |
| 1 | `GET /api/notification-settings`: 현재 설정·동의·최신 `consentPolicyVersion` 확인 |
| 2 | 필요한 약관 안내·동의 UI 표시. 브라우저 알림 권한과 서비스 약관 동의는 각각 처리 |
| 3 | 사용자 버튼에서 브라우저 알림 허용 및 서비스 워커 등록 |
| 4 | `GET /api/push/vapid-public-key`의 `publicKey`를 base64url 디코딩하여 `applicationServerKey`로 사용 |
| 5 | `pushManager.subscribe({userVisibleOnly:true, applicationServerKey})` 또는 기존 유효 구독 확보 |
| 6 | `POST /api/push/subscriptions`: 구독의 `toJSON()`과 userAgent 전달 |
| 7 | `PUT /api/notification-settings`: pushEnabled=true와 필요한 최신 동의 전달 |

구독 요청:

```json
{
  "endpoint": "브라우저가 발급한 HTTPS endpoint",
  "keys": { "p256dh": "브라우저 공개 키", "auth": "브라우저 auth 값" },
  "userAgent": "브라우저 정보"
}
```

처음 201, 같은 endpoint 갱신은 200이며 응답은 `{subscriptionId}`다.
켜기: `{ "pushEnabled": true, "notificationConsent": { "agreed": true, "policyVersion": "조회한 최신 버전" } }`.
끄기: `{ "pushEnabled": false }`. 끄기는 동의 철회와 **이 회원의 모든 브라우저 구독 삭제**를 포함한다.
브라우저가 권한을 거절했으면 서버 설정을 켜지 않는다. 구독 저장이나 설정 변경 실패를 완료로 표시하지 않는다.
GET 설정 응답의 `consentPolicyVersion=null`이면 시행 중인 알림 약관부터 서버에 준비해야 한다.
현재 회원 공통 약관 응답에는 termsType·버전 식별 정보가 없으므로 약관 원문 표시 방식은 회원/프론트 담당과 맞춰야 한다.

서비스 워커는 서버가 보내는 `{title,body,url,tag}`로 알림을 표시하고, 클릭하면 동일 출처의 `/doses?date=YYYY-MM-DD&time=HH:mm`로 이동한다.
프론트는 이 라우트를 제공하고 그 날짜·시각으로 `/api/calendar/doses`를 조회한다. 새 탭에 로그인 상태가 없으면 로그인 후 이동을 이어간다.
알림 payload에는 약 이름·프로필 이름이 없다. 구독 endpoint·auth 값·회원 JWT를 로그나 URL에 넣지 않는다.
서비스 워커 배포·범위는 **프론트 출처** 기준이다. 서버에 API가 있다고 프론트 서비스 워커가 자동 등록되지는 않는다.

서버는 매분 0초 KST에 회원별 한 건을 구독 브라우저로 보낸다. 같은 분은 중복 발송하지 않고 지난 시각을 소급 발송하지 않는다.
404·410인 오래된 구독은 서버에서 제거한다. 브라우저에서 해제한 구독은 다시 발급·저장해야 한다.

## 인수 확인

- 일정 생성 → 상세/캘린더/복용 예정 목록 조회 → 전체 교체 → 그만 먹기 → 내일 제외 → 삭제 → 약 행 유지 → 재등록.
- 프로필을 바꿔도 계정 캘린더에 전체 프로필 표시, 같은 프로필의 색상 유지.
- Live 기본 설정 제안은 동의 전 DB 미반영, 동의/위임 후 실제 반영, 삭제 성공의 null savedSchedule 처리.
- Live 중복 시작·페이지 이탈·마이크 거절·60초 만료·재생 차단·완료 후 자원 해제.
- 알림 거절·최신 약관 동의·구독/설정 실패·실제 정시 수신·클릭 이동·끄기 후 발송 제외.
- 401/404/409/503 처리. 사용자 발화·UI 멘트만으로 성공 표시하지 않기.

현재 검증 결과와 미확인 항목은 [백엔드 검증 기록](MEDICATION_VERIFICATION.md)에 구분한다.
