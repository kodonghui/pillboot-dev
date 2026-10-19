# GPT-Live 복약 일정 등록

기존 단발 음성 API와 별도로 GPT-Live 음성 대화를 연결합니다. 브라우저는 WebRTC로 음성을 주고받고,
서버는 sideband WebSocket으로 사용자·답변 자막과 client delegation 이벤트를 받아 기존 일정 저장 서비스를 호출합니다.
새 테이블이나 라이브러리는 추가하지 않습니다. 원본 음성과 대화·초안은 DB·파일·로그에 저장하지 않습니다.

## 실행

1. 기존 일정 DB 변경(03)이 적용된 로컬 DB와 로그인 JWT를 준비합니다.
2. 개인 `.env`에 `OPENAI_API_KEY`, `OPENAI_CHAT_MODEL`을 지정합니다. 후자는 구조화 출력 지원 모델이어야 합니다.
3. Live 모델은 `OPENAI_LIVE_MODEL`, 기본 `gpt-live-1`입니다. 음색은 `OPENAI_LIVE_VOICE`, 기본 `marin`입니다.
4. 서버를 local 프로필로 재시작하고 `http://localhost:8080/dev/medication-live`를 엽니다.
5. 회원 JWT, 해당 회원의 프로필·내 약 ID, 캘린더에서 선택한 날짜를 넣고 음성 대화를 시작합니다.

OpenAI 모델에 대한 실제 프로젝트 사용 권한과 연결 성공은 호출 시 확인됩니다. 키·해석 모델이 없으면 세션 생성 전에 거절해야 합니다.
이 화면은 OpenAI API 키를 받지 않습니다. 회원 JWT는 브라우저 메모리에서만 사용하며 저장소에 보관하지 않습니다.
음성 대화는 실제 세션 사용 요금과 backend Responses 호출 요금이 발생합니다.
마이크는 localhost 또는 HTTPS에서 권한을 받아야 하며, 자동 음성 재생이 막히면 화면의 오디오 재생 버튼을 누릅니다.

## 동작 규칙

- 시작 인사부터 평소의 약 1.5배 속도로 말하도록 Live에 지시합니다. 날짜·시간·개수는 또렷하게 발음하고, 사용자가 속도 변경을 요청하면 따릅니다. 이는 말하기 스타일 지시이며 정확한 재생 배속을 보장하지 않습니다. 서버 재시작 후 새 대화부터 적용됩니다.
- 시작일을 말하지 않으면 이미 화면에서 선택한 날짜를 사용합니다. 약도 선택값을 사용하며 항상 회원 소유인지 검사합니다.
- 종료일 또는 기간, 구체 복용 시각(1~3개), 1회 개수(1~10)가 없으면 한 항목씩 물어봅니다. 필수 정보가 모이기 전에는 저장하지 않습니다.
- “하루 세 번”, “아침·저녁”만으로 시각을 임의로 정하지 않습니다. 구체적인 시각을 질문합니다. 복용 때는 선택이므로 없어도 됩니다.
- “알아서 저장해”, “기본값으로 해줘”처럼 맡긴 경우에만 빠진 항목에 기본값을 적용합니다.
  AI의 useDefaults 플래그뿐 아니라 실제 사용자 자막의 근거도 검사합니다. assistant가 제안한 말을 사용자 허락으로 취급하지 않습니다.
- “몰라”만 말하면 기본값으로 저장할지 묻습니다. 직전 기본값 저장 질문에 명확히 “네”라고 동의해도 허용합니다.
- 기본값은 선택 날짜 하루, 07:00·11:00·17:00, 현재 약의 1회 개수·복용 때입니다. 횟수 N을 이미 말했다면 기본 시각의 앞 N개만 사용합니다.
- 이미 정한 날짜·기간·시각·개수는 보존합니다. 잘못된 시각·역전된 기간·범위 밖 개수는 기본값으로 덮지 않고 다시 묻습니다.
- 음성은 저장(등록·전체 교체)만 지원합니다. 같은 약의 기존 일정은 PUT처럼 교체됩니다. 실제 약·일정 삭제나 그만 먹기는 실행하지 않습니다.
- 저장 중 새 발화가 들어와 해석 시점의 사용자 자막 버전이 달라졌으면 이전 결정은 폐기합니다.
  다음 delegation에서 정정된 전체 대화를 다시 해석합니다. 이미 커밋된 저장을 음성 중단만으로 되돌리지는 않습니다.
- 동일 delegation ID와 같은 사용자 자막 버전에 대한 중복 요청은 재저장하지 않습니다. 저장 실패 결과도 자동 재시도하지 않습니다.
- 저장 성공을 기존 서비스의 트랜잭션이 반환한 뒤에만 Live에 완료 문구를 전달합니다. Live 경로는 별도 TTS API를 호출하지 않습니다.

## API

모든 업무 API는 기존 회원 Bearer JWT가 필요합니다.

| 요청 | 동작 |
| --- | --- |
| `POST /api/medication/live/sessions` | 선택값·SDP offer로 시작. 201과 로컬 sessionId·SDP answer·expiresAt |
| `GET /api/medication/live/sessions/{sessionId}` | 본인 대화의 상태·질문·부족 항목·적용 기본값·마지막 저장 결과 |
| `DELETE /api/medication/live/sessions/{sessionId}` | 추가 저장 차단 후 원격 연결 종료. 204. 이미 저장한 일정은 유지 |

POST 본문은 `{ "sdp": "<browser offer>", "selectedProfileId": 13, "selectedMyDrugId": 25, "selectedDate": "2026-11-03" }`입니다.
실제 SDP는 브라우저가 생성해야 하므로 Swagger에서 문자열을 임의로 넣어 음성 연결을 검사할 수는 없습니다.
다른 회원·없는 세션은 동일하게 404이고, 같은 회원의 중복 대화 시작은 409입니다.

로컬 화면과 JS만 GET 공개이며, 컨트롤러 자체도 local 프로필에만 존재합니다. 업무 API를 permitAll로 풀지 않습니다.
client data channel은 자막·연결 상태 관찰과 session.close만 허용합니다. 서버 업무 판단은 backend가 맡습니다.

## 연결·운영 범위

- 서버 키로 `POST /v1/live/sessions`를 호출하고 `session.id`로 `/v1/live/sessions/{id}/attach`에 접속합니다.
- 입력·출력 transcript delta를 타임스탬프와 함께 수집합니다. delegation 이벤트에는 사용자 문장이 없으므로 실제 자막과 선택값으로 backend 입력을 만듭니다.
- 서버가 `session.commentary.append`로 질문·저장 결과를 돌려주며, 브라우저는 서버 상태를 조회해 캘린더를 갱신합니다.
- 회원당 대화 하나, 서버 전체 기본 10개입니다. `OPENAI_LIVE_MAX_SESSIONS`로 조정합니다.
- 대화 제한은 `OPENAI_LIVE_MAX_SECONDS`, 기본 300초입니다. 브라우저 타이머, 서버 상태 조회·저장 직전 검사, 10초 주기 정리로 종료합니다.
- 종료 버튼은 서버의 추가 저장을 차단하고 hangup합니다. 최종 usage 이벤트를 기다려 집계하는 기능은 이번 버전에 포함하지 않습니다.
- 세션 상태는 단일 서버 메모리입니다. 서버 재시작 시 대화는 복구되지 않습니다. 여러 서버 운영에는 라우팅·공유 상태 설계가 추가로 필요합니다.
- 자막은 일부 문장일 수 있고 인식 오류가 있을 수 있습니다. 실제 사용자 발화로 질문·위임·정정·저장 결과를 확인하는 실연동 검증이 필요합니다.

## 검증

`./gradlew test bootJar`와 `node scripts/test-medication-live.cjs`로 검증합니다.
Java 테스트는 세션 생성 HTTP, WebSocket 조각 조립, 회원 소유권, 정보 부족, 기본값 위임, 정정·종료 시 저장 방지와 중복 방지를 mock·로컬 fixture로 검사합니다.
브라우저 동작 테스트는 Node 가짜 DOM·WebRTC로 토큰 누락, 연결 도중 종료, 늦은 세션 정리, API 오류와 미디어 해제를 검사합니다.
실제 과금 세션·마이크·프로젝트 모델 권한은 이 검증과 별개이며 자동 테스트에서 호출하지 않습니다.

공식 근거: [Live 연결](https://developers.openai.com/api/docs/guides/voice-webrtc),
[client delegation](https://developers.openai.com/api/docs/guides/live-delegation),
[서버 sideband](https://developers.openai.com/api/docs/guides/voice-server-controls),
[세션 생성](https://developers.openai.com/api/reference/resources/live/methods/create).
