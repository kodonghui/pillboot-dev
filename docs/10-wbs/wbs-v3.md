# 필받음 v3 WBS

- 상태: 초안(2026-11-02). 입력은 [v3 기능 명세](../04-functional-spec/spec-v3.md) 39행(2026-11-02 V3-CHAT-008 추가)과 REG-003 변경, [v3 요구사항](../02-prd/requirements-v3.md), [v3 사용자 흐름](../03-user-flow/user-flow-v3.md).
- 개발 방식은 DEC-017을 따름: v3 범위(1~4차)를 모두 개발하고, 기능마다 브랜치를 따서 작업한 뒤 PR로 `develop`에 병합함. `main` 반영은 따로 정함.
- 담당 칸은 CT 배정 초안임. 팀원이 브랜치를 맡으면 담당 칸만 바꿈. 예상은 한 사람 기준 작업일임.

## 1. 기능 브랜치

| 브랜치 | 차수 | 명세 | 시작 조건 |
| --- | --- | --- | --- |
| `feature/v3-drug-strength` | 1차 | V3-DRUG-001·002·004, V3-REG-001·002 | 바로 |
| `feature/v3-mydrug-bulk-delete` | 1차 | V3-DRUG-003, V3-COMMON-003 | 바로 |
| `feature/v3-time-input` | 1차 | V3-SCHED-001·002 | 화면 승인 뒤 |
| `feature/v3-wait-indicator` | 1차 | V3-COMMON-001 | 화면 승인 뒤 |
| `feature/v3-dur-tooltip` | 1차 | V3-COMMON-002 | 화면 승인 뒤 |
| `feature/v3-relation-other` | 공통 | V3-COMMON-004 | 바로 |
| `feature/v3-chat-registration` | 2차 | V3-CHAT-001~006 | `feature/v3-drug-strength` 병합 뒤 |
| `feature/v3-chat-schedule` | 2차 | V3-CHAT-007 | `feature/v3-chat-registration` 병합 뒤 |
| `feature/v3-chat-progress` | 2차 | V3-CHAT-008 | 바로(서버), 화면 승인 뒤(프론트). DEC-021 |
| `feature/v3-kakao-server` | 3차 | V3-KAKAO-002·005·006·010의 서버 쪽 | 11/5 이후 H-14 확인 뒤 |
| `카톡 다리` 저장소 | 3차 | V3-KAKAO-001~010의 다리 쪽 | 위와 같음. 그 저장소의 브랜치 규칙은 시작 때 확인함 |
| `feature/v3-appointment` | 4차 | V3-APPT-001~007, V3-COMMON-003 | 바로(서버), 화면 승인 뒤(프론트) |
| `feature/v3-appointment-reminder` | 4차 | V3-NOTI-001·002 | `feature/v3-appointment` 병합 뒤 |

## 2. 브랜치 규칙

- 최신 `origin/develop`에서 브랜치를 따고, 끝나면 `develop`으로 PR을 엶. PR 제목이나 본문에 TASK·명세 ID를 적음.
- 계약을 먼저 씀: 같은 브랜치의 첫 커밋에 SQL 패치와 `docs/06-api/openapi-v3.yaml`의 해당 경로를 넣고, 그 뒤에 코드를 씀. 기존 JSON 계약과 물리명(`trun_no`, `item_seq2`, `material`)은 바꾸지 않음.
- SQL 패치 번호는 겹치지 않게 미리 나눔: 14 함량, 15 관계 값(필요할 때만), 16 등록·일정 제안, 17 진료 일정, 18 사전 알림. SQL을 실제 DB에 적용하는 일과 배포는 따로 요청받아 함.
- 화면이 바뀌는 일은 `react-client/design/`의 화면정의서와 목업을 먼저 쓰고, PM이 실제 캡처를 보고 승인한 뒤 프론트를 붙임(DEC-008).
- PR을 열면 Codex 자동 리뷰가 돎. 지적을 고치고 검사가 통과하면 `develop`에 병합함. 검사: 서버는 `./gradlew test`, 프론트는 `npm run lint`·`npm run build`.
- API를 건드린 작업은 그 API가 내는 응답마다 통과한 시험이 있어야 끝남(성공만이 아님).

## 3. 작업

| ID | 작업 | 명세 | 닿는 곳 | 먼저 할 것 | 담당 | 예상 | 끝난 조건 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TASK-001 | 함량 분리기: 품목명에서 기본 이름·함량 원문·μg 값·표시 단위를 뗌 | V3-DRUG-001 | drug 공통 코드(`PrescriptionDrugLookup` 규칙 재사용) | 없음 | Claude(CT) | 0.5일 | '알프람정 10mg', '500/125mg', %·IU·mL, 함량 없음 예시가 단위 시험으로 통과함 |
| TASK-002 | `drug` 함량 칸 SQL 패치 14와 캐시 저장 | V3-DRUG-001 | `drug`, `sql/14` | TASK-001 | Claude(CT) | 0.5일 | 새 DB(00→01→13→14)와 기존 DB 갱신에 패치가 적용되고, 캐시 저장 때 네 칸이 채워지는 MySQL 시험이 통과함 |
| TASK-003 | 기존 약 함량 채우기 실행기 | V3-DRUG-002 | `drug` | TASK-002 | Claude(CT) | 0.5일 | 빈 행을 채우고 수를 출력하며, 두 번째 실행의 채운 수가 0인 시험이 통과함 |
| TASK-004 | 응답에 기본 이름·함량 칸 추가 | V3-DRUG-004 | `GET /api/drugs/search`, `GET /api/drugs/{itemSeq}`, `GET /api/profiles/{profileId}/drugs`, `GET /api/profiles/{profileId}/drugs/{myDrugId}` | TASK-002 | Claude(CT) | 0.5일 | 기존 칸이 그대로이고 새 칸이 더해진 응답 시험이 통과함 |
| TASK-005 | 함량 뗀 이름 검색과 일치 후보 앞으로 | V3-REG-001 | `GET /api/drugs/search` | TASK-001 | Claude(CT) | 1일 | 수용 기준과 2자 미만·결과 없음 재조회·공공데이터 장애 오류 시험이 통과함 |
| TASK-006 | 후보 함량 상태(일치·다름·확인 불가)와 자동 선택(REG-003 변경) | V3-REG-002 | 처방전 판독 후보(`POST /api/profiles/{profileId}/photo-identifications` 처방전, `GET /api/photos/{photoId}/candidates`, 후보의 기본 이름·함량 칸과 자동 선택 칸 포함) | TASK-001 | Claude(CT) | 1일 | 수용 기준과 복합제 원문 일치 시험이 통과하고 기존 판독 시험이 깨지지 않음 |
| TASK-007 | 화면정의서·목업: 이름·함량 표시와 '함량 다름' 후보 | V3-DRUG-004, V3-REG-002 | 약 검색·판독 후보·약목록·약 상세 화면 | TASK-004·006의 계약 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-008 | 프론트: 이름·함량 표시와 '함량 다름' 후보 | V3-DRUG-004, V3-REG-002 | 위 화면 | TASK-004·006·007 | Claude(CT) | 1일 | 화면이 정의서 상태대로 보이고 lint·build가 통과함 |
| TASK-009 | 여러 개 삭제 API 계약 | V3-DRUG-003 | `openapi-v3.yaml`의 `POST /api/profiles/{profileId}/drugs/bulk-delete` | 없음 | Claude(CT) | 0.5일 | 200·400·401·404 응답과 오류 모양이 계약에 있음 |
| TASK-010 | 서버: 여러 개 삭제(한 트랜잭션, 일정·알림 함께) | V3-DRUG-003, V3-COMMON-003 | 위 API, `my_drug`, 복약 일정·알림 | TASK-009 | Claude(CT) | 1일 | 200·400·401·404마다 시험이 통과하고 하나라도 실패하면 아무것도 안 지워짐 |
| TASK-011 | 화면정의서·목업: 약목록 선택 모드와 확인 창 | V3-DRUG-003 | 약목록 화면 | TASK-009 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-012 | 프론트: 선택과 여러 개 삭제 | V3-DRUG-003 | 약목록 화면 | TASK-010·011 | Claude(CT) | 1일 | 실제 서버로 지우고 404면 목록을 다시 불러오며 lint·build가 통과함 |
| TASK-013 | 화면정의서·목업: 복약 시각 +/−와 숫자 입력 | V3-SCHED-001·002 | 복약 일정 입력 화면 | 없음 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-014 | 프론트: 복약 시각 입력 | V3-SCHED-001·002 | 복약 일정 입력 화면(`scheduleInfo.js`) | TASK-013 | Claude(CT) | 1일 | `830`→`08:30`, `2460` 안내·저장 막힘, 08:55 분+ → 09:00 시험과 lint·build가 통과함 |
| TASK-015 | 화면정의서·목업: 기다림 표시 | V3-COMMON-001 | 사진 판독·약 검색·답변 화면 | 없음 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-016 | 프론트: 기다림 표시 | V3-COMMON-001 | 위 화면 | TASK-015 | Claude(CT) | 1일 | 2초 뒤 단계 이름·경과 초가 보이고, 화면 읽기 알림이 단계가 바뀔 때만 나며 lint·build가 통과함 |
| TASK-017 | 화면정의서·목업: 금기 표시 설명 | V3-COMMON-002 | 금기 표시가 있는 화면 | 없음 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-018 | 프론트: 금기 표시 설명 | V3-COMMON-002 | 위 화면(`chatInfo.js` 이름과 같게) | TASK-017 | Claude(CT) | 0.5일 | 누름·키보드로 열고 Esc·바깥 누름으로 닫히며 lint·build가 통과함 |
| TASK-019 | 서버: 관계 값 `OTHER` | V3-COMMON-004 | `POST /api/profiles`, `PUT /api/profiles/{profileId}`. `relationship_type`이 varchar(10)이고 제약이 없어 `sql/15`는 필요 없음 | 없음 | Claude(CT) | 0.5일 | `OTHER` 저장(만들기 201·고치기 200)과 목록 밖 값 400 시험이 통과함 |
| TASK-020 | 프론트: 관계 선택 '지인·기타' | V3-COMMON-004 | 프로필 화면(정의서 한 줄 고침) | TASK-019 | Claude(CT) | 0.5일 | 선택하고 저장되며 lint·build가 통과함 |
| TASK-021 | 등록·일정 제안 SQL 패치 16과 등록 방법 `CHAT` | V3-CHAT-002·003·005·007 | 제안 테이블(H-10은 여기서 정함), `my_drug.source` | 없음 | Claude(CT) | 1일 | 새 DB·기존 DB에 적용되고, 한 제안이 두 번 확정되지 않게 DB 제약이 있음 |
| TASK-022 | 제안 API 계약: 조회·확정·취소 | V3-CHAT-002~005·007 | `openapi-v3.yaml` | TASK-021 | Claude(CT) | 0.5일 | 200·400·401·404·409 응답이 계약에 있음 |
| TASK-023 | 질문 의도 가르기(AI 호출 8.1) | V3-CHAT-001 | 질문 처리(`QuestionService`) | 없음 | Claude(CT) | 1일 | 등록 요청·약 질문 예시와 AI 실패 때 '약 질문'으로 넘기는 시험이 통과함 |
| TASK-024 | 등록 제안 만들기 | V3-CHAT-002 | 질문 처리, 제안 테이블 | TASK-005·021·023 | Claude(CT) | 1일 | 수용 기준과 후보 없음·공공데이터 장애·11개 이상 시험이 통과함 |
| TASK-025 | 등록 제안 확정·취소·만료 | V3-CHAT-003~005 | 제안 API, `my_drug` | TASK-021·022 | Claude(CT) | 1.5일 | 재확정 409, 취소·만료 409, 동시 확정 하나만 성공 시험이 통과함 |
| TASK-026 | 상담방 사진으로 제안 | V3-CHAT-006 | 처방전 사진 식별, 제안 | TASK-006·024 | Claude(CT) | 1일 | 확정 전 약목록이 그대로이고 판독 실패·후보 없음 안내 시험이 통과함 |
| TASK-027 | 화면정의서·목업: 대화 속 등록 제안 카드 | V3-CHAT-002~006 | 상담 화면 | TASK-022 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-028 | 프론트: 등록 제안 카드와 확정·취소 | V3-CHAT-002~006 | 상담 화면 | TASK-025·027 | Claude(CT) | 1.5일 | 실제 서버로 확정·취소하고 409·만료 안내가 보이며 lint·build가 통과함 |
| TASK-029 | 서버: 대화로 일정 바꾸기(일정 제안·확정) | V3-CHAT-007 | 제안 API, 복약 일정 | TASK-025 | Claude(CT) | 1.5일 | 확정 전 일정이 그대로이고, 확정 사이 일정이 바뀌면 409인 시험이 통과함 |
| TASK-030 | 화면정의서·목업: 일정 확인 카드 | V3-CHAT-007 | 상담 화면 | TASK-022 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-031 | 프론트: 일정 확인 카드 | V3-CHAT-007 | 상담 화면 | TASK-029·030 | Claude(CT) | 1일 | 확정 뒤 캘린더가 바뀌고 lint·build가 통과함 |
| TASK-032 | 서버: 음성 받아쓰기 API | V3-KAKAO-006 | 새 API(H-02 추천: 필받음 서버) | H-01·02 확인 | Claude(CT) | 1일 | 1분 초과·빈 결과·실패 응답 시험이 통과하고 원본 음성을 남기지 않음 |
| TASK-033 | 서버: 카카오 연결이 쓰는 경로 점검(자동 프로필·제안 확정·프로필 삭제) | V3-KAKAO-002·005·010 | 기존 프로필·제안 API | TASK-019·025 | Claude(CT) | 0.5일 | 약봉이 계정으로 각 경로를 부르는 시험이 통과함 |
| TASK-034 | 다리: 시작 안내·정보 묻기·자동 프로필 연결 | V3-KAKAO-001·002 | `카톡 다리` | TASK-033 | Claude(CT) | 1.5일 | 수용 기준 시험이 통과함 |
| TASK-035 | 다리: 메시지 묶기·기능 스위치·턴 기록 | V3-KAKAO-003·008·009 | `카톡 다리` | TASK-034 | Claude(CT) | 1.5일 | 1,000자 상한·묶음 하나에 답 하나·로그에 내용 없음 시험이 통과함 |
| TASK-036 | 다리: 카톡 사진 제안과 빠른 답장 확정·취소 | V3-KAKAO-004·005 | `카톡 다리` | TASK-026·035 | Claude(CT) | 1일 | '모두 등록' 두 번에 첫 번째만 저장되는 시험이 통과함 |
| TASK-037 | 다리: 음성 받아쓰기와 카톡으로 일정 바꾸기 | V3-KAKAO-006·007 | `카톡 다리` | TASK-029·032·035 | Claude(CT) | 1일 | 음성이 글과 같은 제안을 내고, 확정 전 일정이 그대로인 시험이 통과함 |
| TASK-038 | 다리: 연결 해제와 삭제(해제 기록) | V3-KAKAO-010 | `카톡 다리` | TASK-033·034 | Claude(CT) | 1일 | 해제 뒤 삭제·실패 재시도·404를 지워진 것으로 보는 시험이 통과함 |
| TASK-039 | 진료 일정 SQL 패치 17과 API 계약 | V3-APPT-001~003·006 | 진료 일정 테이블, `openapi-v3.yaml` | 없음 | Claude(CT) | 1일 | 새 DB·기존 DB에 적용되고 200·400·401·404 응답이 계약에 있음 |
| TASK-040 | 서버: 진료 일정 등록·변경·삭제 | V3-APPT-001~003, V3-COMMON-003 | 진료 일정 API | TASK-039 | Claude(CT) | 1.5일 | 동행자 규칙·소유권 404·지난 진료 시험이 통과함 |
| TASK-041 | 서버: ics 내려받기 | V3-APPT-006 | 진료 일정 API | TASK-040 | Claude(CT) | 0.5일 | `Asia/Seoul` 시각과 고정 고유 ID 시험이 통과함 |
| TASK-042 | 화면정의서·목업: 진료 일정 입력·상세·캘린더 표시·구글 링크·사본 안내 | V3-APPT-001~005·007 | 캘린더·진료 일정 화면 | TASK-039 | Claude(CT) | 1일 | PM이 캡처를 보고 승인함 |
| TASK-043 | 프론트: 진료 일정 화면 | V3-APPT-001~005·007 | 위 화면 | TASK-040·041·042 | Claude(CT) | 2일 | 같은 날 복약과 진료가 모양으로 구분되고 lint·build가 통과함 |
| TASK-044 | 사전 알림 SQL 패치 18과 알림 시각 계산 | V3-NOTI-001, V3-APPT-001·002 | 사전 알림 테이블(선점 키: 진료 일정·알림 종류·진료 시각) | TASK-040 | Claude(CT) | 1일 | 11/20 14:00·10:00 예시와 지난 시각·미룬 진료 시험이 통과함 |
| TASK-045 | 사전 알림 선점과 웹 푸시 발송 | V3-NOTI-002 | 스케줄러, 웹 푸시 | TASK-044 | Claude(CT) | 1.5일 | 실행 둘이 같은 알림을 한 번만 보내고, 구독·동의 없음은 보내지 않는 시험이 통과함 |
| TASK-046 | v3 QA 시험 계획과 흐름 점검 | V3-FL-17~29 | 12 QA | 차수마다 병합 뒤 | Claude(CT) | 차수마다 0.5일 | 흐름마다 시험 사례가 있고 결과가 기록됨 |
| TASK-047 | 답변 진행 단계 SSE 계약과 서버 | V3-CHAT-008 | `POST /api/chat-sessions/{sessionId}/messages/stream`, `.../{messageId}/retry/stream`, `QuestionService`·`AnswerGenerator` | 없음 | Claude(CT) | 1일 | 단계·result·error 이벤트와 열기 전 JSON 오류(400·401·404·409) 시험이 통과하고 기존 JSON 질문 시험이 그대로 통과함 |
| TASK-048 | 화면정의서·목업: 답변 진행 단계 표시 | V3-CHAT-008, V3-COMMON-001 | 상담 화면(CHT-02 답 기다림) | TASK-047의 계약 | Claude(CT) | 0.5일 | PM이 캡처를 보고 승인함 |
| TASK-049 | 프론트: fetch로 SSE 읽기와 단계 표시 | V3-CHAT-008 | 상담 화면, `api/chat.js` | TASK-047·048 | Claude(CT) | 1일 | 실제 서버로 단계가 바뀌어 보이고, 503·409·연결 끊김이 기존 안내대로 가며 lint·build가 통과함 |

## 4. 점검

| 점검 | 결과 |
| --- | --- |
| 명세 행마다 작업이 있는가 | 39행 모두 작업에 있음(스크립트 대조, V3-CHAT-008 추가 뒤 다시 대조) |
| 작업마다 담당 하나와 다른 사람이 확인할 수 있는 끝난 조건이 있는가 | 있음 |
| 의존에 순환이 없는가 | 없음(스크립트 대조) |
| API를 건드린 작업의 끝난 조건에 응답별 시험이 있는가 | 있음(2장 규칙과 각 행) |
