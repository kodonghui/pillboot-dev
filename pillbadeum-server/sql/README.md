# ERD Cloud 기준 MySQL 초기 설정

DB 정의의 기준은 사용자가 제공한 ERD Cloud의 MySQL 내보내기 원문입니다.
**관계형 14개 테이블·114개 컬럼**이며 `vector_point`는 별도 Qdrant 모델입니다.
사용자 결정으로 복약 알림용 `push_subscription`·`notification_dispatch`를 더해 현재 01은 16개 테이블·124개 컬럼입니다.
다른 노션 ERD나 API를 근거로 표·컬럼을 추가하지 않습니다.

| 파일 | 용도 |
| --- | --- |
| `00_PILLBADEUM_DB_CREAT.sql` | 관리자 연결에서 로컬 pilldb·개발 계정 준비 |
| `01_PILLBADEUM_DB_SCRIPT.sql` | 비어 있는 개발 DB에 ERD Cloud 구조 생성 |
| `02_PILLBADEUM_TEST_DATA.sql` | 선택 실행. 빈 테이블에 DB·화면 확인용 샘플 입력 |
| `03_MY_DRUG_OPTIONAL_SCHEDULE.sql` | 기존 14개 테이블 DB 전용. 약을 유지하고 일정만 삭제할 수 있도록 세 컬럼과 CHECK 변경 |
| `04_PUSH_NOTIFICATION.sql` | 기존 DB 전용, 03 다음. 알림 설정 회원당 1행 UNIQUE, 푸시 구독·발송 시각 표 추가 |
| `05_FIX_COLUMN_TYPOS.sql` | 기존 DB 전용, 04 다음. 컬럼명 오타 realationship_type·matrial을 relationship_type·material로 변경 |

00을 실행한 뒤 대상 DB를 선택하여 01을 실행합니다. 샘플이 필요하면 빈 테이블에서만 02를 한 번 실행합니다.
앱은 SQL을 자동 실행하지 않으며 JPA ddl-auto는 none입니다. IF NOT EXISTS는 기존 컬럼·제약을 변경하지 않습니다.
기존 10개/15개 테이블 DB에는 그대로 실행하지 말고 백업 후 새 DB에서 먼저 확인합니다.
이전 API 확장 구조를 적용하던 자동 업그레이드 파일은 제거했습니다.
기존 14개 테이블 DB에서 일정 삭제를 쓰려면 대상 DB를 선택하고 03을 한 번 적용합니다.
새 01에는 같은 변경이 포함되므로 03을 추가 실행하지 않습니다. 앱이 03을 자동 실행하지 않습니다.
복약 알림을 쓰려면 기존 DB에 04도 한 번 적용합니다. 같은 회원의 알림 설정이 여러 행이면 먼저 정리해야 합니다.

## 원문과 실행 SQL의 관계

- 최신 ERD Cloud에 맞춰 테이블명과 모든 SQL 참조를 소문자로 통일했습니다. trun_no·item_seq2는 원문 그대로이며, 팀 결정으로 오타 realationship_type·matrial은 relationship_type·material로 바로잡았습니다(기존 DB는 05 적용).
- consent.terms_title의 VARHCHAR(30)는 유효한 자료형 VARCHAR(30)으로 교정했습니다.
- 사용자 결정에 따라 chat_session.snap_pregnant는 BOOLEAN으로 통일했습니다. 기존 NULL 허용과 기본값 없음은 유지합니다.
- 최신 내보내기의 photo.kind VARCHAR(30)을 반영했습니다.
- 최신 내보내기의 PK/FK는 Qdrant를 제외해 PK 14개·FK 15개로 반영했습니다. 알림 표를 더해 현재 PK 16개·FK 16개입니다.
- push_subscription.endpoint는 브라우저마다 1행이 되도록 UNIQUE이며 대소문자를 구분합니다(ascii_bin).
  notification_dispatch는 발송한 복용 시각(분)만 기본키로 남겨 같은 분의 중복 발송을 막습니다. 누구에게 무엇을 보냈는지는 저장하지 않습니다.
  AUTO_INCREMENT가 생성 시점부터 유효하도록 PK를 CREATE TABLE 안에 배치합니다.
- 사용자 결정에 따라 drug.etc_otc의 UNIQUE는 제거하고, NOT NULL과 chk_drug_type CHECK (etc_otc IN ('ETC', 'OTC'))를 적용했습니다.
- 일정 한 건 삭제 시 약을 유지하도록 my_drug.start_date/end_date/dose_times는 NULL 허용·기본값 NULL로 변경했습니다.
  세 값이 모두 NULL이거나 모두 값이 있도록 chk_my_drug_schedule_presence CHECK를 적용했습니다.
  기본 복용 시각 07:00·11:00·17:00은 기간을 지정한 약 등록 시 앱에서 채웁니다. 표·컬럼 수는 바뀌지 않습니다.
- 위 교정·결정 외 자료형·길이·NULL·기본값·제약은 유지하며 추가 CHECK·복합 UNIQUE·CASCADE·ON UPDATE는 넣지 않습니다.
- ERD 밖 push_subscription, 프로필 색상, 약 상세 확장, 사진 앞뒤 연결, 후보 약 참조 컬럼은 제거했습니다.
- 기본값이 없는 필수 생성·수정 시각 등은 저장 코드에서 직접 채워야 합니다.
- Linux MySQL에서는 표명 대소문자를 구분하므로 실제 이름으로 조회합니다.

## 약 분류와 기존 DB

drug.etc_otc는 내부 저장 코드 ETC(전문)·OTC(일반)만 허용합니다. 같은 분류의 약은 여러 개 저장할 수 있습니다.
공공데이터 원문의 분류 표기가 다르면 약 저장 기능에서 내부 코드로 변환한 뒤 저장해야 합니다.
샘플 약은 기존과 같이 분류별 1개, 총 2개입니다.

photo.kind는 VARCHAR(30)으로 PRESCRIPTION(12자)을 저장할 수 있습니다.
프로필 임신 여부는 원문을 유지합니다. 일정 등록 요청의 시작일·종료일·복용 시각은 필수이며,
일정 없는 약을 보관할 때만 세 컬럼을 함께 NULL로 저장합니다.

대화방 임신 여부 사본은 사용자 결정으로 BOOLEAN NULL로 변경했습니다.
기존 DB에는 01을 재실행해도 표명·자료형·제약이 바뀌지 않습니다. 기존 문자열 TRUE/FALSE, 약 분류값,
실제 표명과 UNIQUE 인덱스를 먼저 확인하고 데이터를 보존하는 별도 변경이 필요합니다.
01은 신규 DB 생성용이며 기존 DB를 자동 변환하지 않습니다. 03은 일정 컬럼 변경만 다룹니다.

## 샘플

회원 5개·프로필 15개·약 2개·내 약 30개·대화방 45개·메시지 180개·요약 45개입니다.
사진·OCR 후보·DUR은 초기 행 없이 준비합니다. 품목 코드·약 이름은 공식 검증되지 않은 DB 확인용 샘플입니다.
실제 공공데이터 호출에는 공식 응답으로 확인한 품목 코드를 별도로 사용합니다.

검토·결정 기록은 개인 노션에 둡니다.
