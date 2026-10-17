# MySQL 초기화와 기존 DB 업그레이드

현재 스키마는 기존 10개 업무 테이블을 보존하고, 첨부 ERD의 `photo`, `ocr_candidate`,
`dur_warning`, `dur_interaction`과 API의 `push_subscription`을 더한 **MySQL 15개**입니다.
첨부 이미지의 관계형 테이블은 14개지만 웹 푸시 구독 저장소가 없으므로 1개를 보완했습니다.
`vector_point`는 Qdrant 포인트/payload이며 MySQL 테이블을 만들지 않습니다.

기준 자료: API 명세,
프로젝트 설계 문서,
사용자가 공유한 ERD 이미지 및 ERD v4의 저장 항목.

| 파일 | 실행 목적 |
| --- | --- |
| `00_PILLBADEUM_DB_CREAT.sql` | 관리자 연결에서 로컬 `pilldb`·개발 계정 준비 |
| `01_PILLBADEUM_DB_SCRIPT.sql` | 신규 DB에 현재 스키마 생성. 없는 테이블만 만들며 기존 정의는 수정하지 않음 |
| `02_PILLBADEUM_TEST_DATA.sql` | 비어 있는 개발 DB에 한 번만 화면/DB 샘플 입력. 선택 실행 |
| `03_PILLBADEUM_UPGRADE_FROM_10_TABLES.sql` | 기존 develop `aa15bf7`의 원본 10개 테이블 DB에 적용하는 1회 변경 |

`01`~`03`은 DB 이름을 강제하지 않습니다. Workbench/클라이언트에서 대상 DB를 먼저 고르세요.
앱은 SQL을 자동 실행하지 않으며 `ddl-auto: none`입니다. 테스트는 별도 `pill_test` DB에서만 실행합니다.
로컬 계정은 팀이 지정한 `pill`/`pill`을 유지하며 `00`은 기존 계정 비밀번호를 변경하지 않습니다.

## 신규 DB

1. 관리자 계정으로 `00` 실행 또는 별도 개발 DB 준비.
2. 대상 DB를 선택하고 `01` 실행.
3. 샘플이 필요하면 **비어 있는 DB에만** `02`를 한 번 실행. 재실행용 UPSERT가 아닙니다.

사진·후보·DUR·푸시 구독은 초기 행이 없어도 됩니다. 실제 기능 흐름에서 생성합니다.
기존 10개 테이블에만 샘플을 넣으며, 채팅은 질문/답변이 같은 턴을 쓰도록 정렬했습니다.
샘플 요약은 저장/화면 검증용 2턴 예시이며 실제 비동기 요약 실행 조건(10턴)을 뜻하지 않습니다.

## 기존 10개 테이블 DB

**`01`을 다시 실행해도 기존 컬럼이나 UNIQUE는 바뀌지 않습니다.** 신규 DB용 파일과 업그레이드를 구분하세요.

1. 대상 DB를 백업하고 다른 앱/팀원의 DB 작성 작업을 멈춥니다.
2. 원본 상태가 `aa15bf7`과 같은지 확인합니다. 이미 별도로 수정했다면 `03`을 곧바로 실행하지 않습니다.
3. 아래 사전 조회로 중복과 기존 채팅 형식을 확인합니다.
4. 대상 DB에서 `03`을 한 번 실행합니다. mysql 클라이언트의 `--force`를 사용하지 마세요.
5. `01`을 다시 실행해도 기존 행이 유지되는지와 기능 저장 흐름을 확인합니다. 기존 DB에 `02`를 다시 넣지 않습니다.

```sql
SELECT profile_id, item_seq, COUNT(*)
FROM my_drug GROUP BY profile_id, item_seq HAVING COUNT(*) > 1;

SELECT session_id, from_turn, COUNT(*)
FROM chat_summary GROUP BY session_id, from_turn HAVING COUNT(*) > 1;

SELECT session_id, turn_no, role, message_id
FROM chat_message ORDER BY session_id, turn_no;
```

`03`은 임시 CHECK 테이블을 이용해 사전 조건이 맞지 않으면 **첫 영구 ALTER 전에 실패**합니다.
중복이 나오면 자동 삭제/병합하지 않습니다. 유지할 내 약·일정을 담당자가 정한 뒤 다시 확인해야 합니다.
MySQL DDL은 전체 파일이 하나의 트랜잭션으로 롤백되지 않습니다. 실행 중 오류가 나면 무작정 재실행하지 말고
마지막 성공한 ALTER와 현재 `SHOW CREATE TABLE`을 확인하여 남은 변경을 적용하세요.

원본 채팅은 `1 USER, 2 ASSISTANT, 3 USER, 4 ASSISTANT`처럼 메시지마다 번호를 올렸습니다.
현재 계약은 `1 USER, 1 ASSISTANT, 2 USER, 2 ASSISTANT`입니다. `03`은 기존 번호가 1부터 연속되고
역할이 교대로 나오는 경우에만 순서를 보존해 번호를 바꿉니다. 마지막 미응답 질문은 허용합니다.
요약은 완성된 쌍의 시작/끝 경계만 변환합니다. 패턴이 다르면 추측하지 않고 중단합니다.
본문·역할·행 ID·시각은 유지합니다. Qdrant에 턴 번호를 payload로 저장했다면 해당 데이터 재색인이 필요합니다.

## 저장과 API 매핑

| 저장 항목 | 기준과 최소 반영 |
| --- | --- |
| `member.email` | API의 최대 100자 허용 |
| `profile.profile_pregnant` | API YES → TRUE, NO → FALSE, 미입력 → NULL. 기존 FALSE 값은 임의 변경하지 않음 |
| `profile.profile_gender`, `chat_session.snap_gender` | 기존 MALE/FEMALE/UNKNOWN 보존. API M/F를 DTO에서 매핑. 기존 UNKNOWN은 별도 확인 대상 |
| `profile.color` | 서버 배정 색을 유지하기 위한 1컬럼. 기존 행은 NULL이며 서비스에서 팔레트를 배정해 저장. 키/몸무게는 삭제하지 않음 |
| `my_drug` | 최초 등록은 일정 없이 가능. `(profile_id,item_seq)` 중복 방지. 일정 삭제 때 날짜/시각은 NULL, 수량/복용 때를 남기는 정책도 허용 |
| `chat_session` | 빈 방 생성 가능, 첫 질문 때 프로필 사본 채움 |
| `chat_message` | `(session_id,turn_no,role)` UNIQUE로 한 턴의 질문/답변 저장 |
| `chat_summary` | `(session_id,from_turn)` UNIQUE로 동일 시작의 중복 요약 방지 |
| `drug` | 허가/낱알 API로 확인한 항목만 저장. 낱알 속성이 없는 약도 허용하도록 선택 필드 NULL 가능 |
| `drug.color`, `drug.color_back` | 기존 color는 앞면(API colorFront)으로 유지, 뒷면만 추가 |
| `drug.main_ingredient`, `material` | API mainIngredient는 유효성분, material은 원료성분 원문. 동일하다고 추정해서 복사하지 않음 |
| `drug.appearance`, `len_long`, `len_short`, `cancel_status` | ERD v4 및 약 상세/식별 후보 재구성에 쓰는 공식 속성. API 응답을 확인한 경우만 채움 |
| `drug.dur_synced_at` | DUR 0건을 포함한 조회 성공 시각. NULL이면 아직 성공하지 않아 추후 재조회 |
| `terms` + `consent_history` | 기존 구조 보존. API의 약관 동의/철회 이력은 AGREED/WITHDRAWN 이벤트로 매핑. 동의 여부는 최신 이력으로 판단 |

전문/일반 구분은 많은 약이 같은 값을 가지므로 `drug.etc_otc`에 UNIQUE를 두지 않습니다.
기존 ETC/OTC 저장값은 공식 전문/일반 값과 명시적으로 매핑하며 미제공은 NULL로 둡니다.
컬럼 길이를 ERD 이미지의 짧은 값으로 일괄 축소하지 않습니다.
외부 API의 필드명 `ITEM_SEQ`와 DB `item_seq`, JSON `itemSeq`는 이름이 달라도 매핑하면 됩니다.
실제 외부 호출에 사용하는 **품목기준코드의 값**은 공식 응답과 일치해야 합니다.

## 임시 사진·후보와 공공데이터 수명

- 사진은 소유 회원/프로필, 종류, UUID 임시 경로, MIME, 크기, 상태, 업로드 시각을 저장합니다.
- 알약 뒷면은 `front_photo_id`로 앞면을 참조합니다. 앞면 삭제는 뒷면과 두 사진의 후보 행을 함께 삭제합니다.
  파일 삭제는 SQL이 수행하지 않습니다. 서비스가 커밋 이후 처리하고 실패 시 재정리해야 합니다.
- 후보는 사진/카드/순위와 공식 약 참조로 재구성합니다. 후보가 없는 카드는 `item_seq=NULL`,
  `candidate_rank=1`, `lookup_status=NOT_FOUND/FAILED`인 한 행으로 저장합니다.
  앞/뒷면 종류·소유자 일치, 후보가 앞면에 붙는지는 서비스에서 검사합니다.
- 사진·후보는 등록 완료/취소/실패/30분 만료 시 정리합니다. 처방전 전체 글자, 환자 정보,
  비전 응답 원문, 원래 파일명은 저장하지 않습니다. 프로필 삭제 시에는 관련 사진/파일도 정리해야 합니다.
- DUR은 원문에서 우리 기능에 필요한 항목만 저장합니다. 상대 약은 로컬 `drug`에 없을 수 있어
  `dur_interaction.mixture_item_seq`에는 FK를 강제하지 않습니다. 재조회 성공 시 약 단위로 기존 DUR과 교체합니다.
- 웹 푸시 구독은 endpoint로 갱신합니다. ASCII URL을 대소문자 구분하여 UNIQUE 처리합니다.
  알림 해제/탈퇴/푸시 서비스 404·410 때 삭제하며 키/endpoint를 로그에 남기지 않습니다.
- 회원/프로필 삭제는 기존 FK 구조에 맞춰 하위 데이터를 먼저 정리하는 트랜잭션으로 구현합니다.
  모든 기존 FK를 일괄 CASCADE로 바꾸지 않습니다.

## 샘플 값의 한계

`02`의 `202600001`~`202600009`와 약 이름/제조사 조합은 원본을 보존한 **검증되지 않은 fixture**입니다.
공식 데이터와 일치하거나 틀리다고 단정하지 않습니다. 이 값으로 실제 API 호출 성공을 보장할 수 없습니다.
통합 테스트용 품목은 담당자가 공식 응답의 코드·제품명·업체명을 함께 확인해 별도로 준비하세요.
기존 BCrypt 해시는 평문 비밀번호를 확인하기 전까지 로그인 성공용 fixture로 간주하지 않습니다.
