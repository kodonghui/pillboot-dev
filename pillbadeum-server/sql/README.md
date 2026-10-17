# ERD Cloud 기준 MySQL 초기 설정

DB 정의의 기준은 사용자가 제공한 ERD Cloud의 MySQL 내보내기 원문입니다.
**관계형 14개 테이블·114개 컬럼**이며 `vector_point`는 별도 Qdrant 모델입니다.
다른 노션 ERD나 API를 근거로 표·컬럼을 추가하지 않습니다.

| 파일 | 용도 |
| --- | --- |
| `00_PILLBADEUM_DB_CREAT.sql` | 관리자 연결에서 로컬 pilldb·개발 계정 준비 |
| `01_PILLBADEUM_DB_SCRIPT.sql` | 비어 있는 개발 DB에 ERD Cloud 구조 생성 |
| `02_PILLBADEUM_TEST_DATA.sql` | 선택 실행. 빈 테이블에 DB·화면 확인용 샘플 입력 |

00을 실행한 뒤 대상 DB를 선택하여 01을 실행합니다. 샘플이 필요하면 빈 테이블에서만 02를 한 번 실행합니다.
앱은 SQL을 자동 실행하지 않으며 JPA ddl-auto는 none입니다. IF NOT EXISTS는 기존 컬럼·제약을 변경하지 않습니다.
기존 10개/15개 테이블 DB에는 그대로 실행하지 말고 백업 후 새 DB에서 먼저 확인합니다.
이전 API 확장 구조를 적용하던 자동 업그레이드 파일은 제거했습니다.

## 원문과 실행 SQL의 관계

- 표명의 대소문자와 trun_no·realationship_type·matrial·item_seq2는 원문 그대로입니다.
- CONSENT.terms_title의 VARHCHAR(30)는 유효한 자료형 VARCHAR(30)으로 교정했습니다.
- 사용자 결정에 따라 chat_session.snap_pregnant는 BOOLEAN으로 통일했습니다. 기존 NULL 허용과 기본값 없음은 유지합니다.
- 최신 내보내기의 PK/FK는 Qdrant를 제외해 PK 14개·FK 15개로 반영했습니다.
  AUTO_INCREMENT가 생성 시점부터 유효하도록 PK를 CREATE TABLE 안에 배치합니다.
- 위 교정·결정 외 자료형·길이·NULL·기본값·UNIQUE를 유지하며 별도 CHECK·복합 UNIQUE·CASCADE·ON UPDATE는 추가하지 않습니다.
- ERD 밖 push_subscription, 프로필 색상, 약 상세 확장, 사진 앞뒤 연결, 후보 약 참조 컬럼은 제거했습니다.
- 기본값이 없는 필수 생성·수정 시각 등은 저장 코드에서 직접 채워야 합니다.
- Linux MySQL에서는 표명 대소문자를 구분하므로 실제 이름으로 조회합니다.

## ERD 자체의 확인 사항

DRUG.etc_otc UNIQUE는 원문 그대로입니다. 같은 ETC 또는 OTC 분류의 약을 두 개 이상 저장하면 중복 오류가 납니다.
샘플 약은 분류별 1개, 총 2개이며 가짜 분류값으로 우회하지 않습니다. 실제 여러 약을 적재하려면
이 제약의 변경을 ERD에 먼저 반영해야 합니다.

PHOTO.kind VARCHAR(10), 프로필 임신 여부·일정 필수값도 원문을 유지합니다.
API의 PRESCRIPTION 12자, 임신 미입력, 일정 없는 등록 등과 다른 부분은
API/기능 명세를 ERD에 맞추거나 ERD 자체를 수정해 함께 정리합니다.

대화방 임신 여부 사본은 사용자 결정으로 BOOLEAN NULL로 변경했습니다.
기존 DB에는 01을 재실행해도 자료형이 바뀌지 않습니다. 기존 문자열 TRUE/FALSE 등의 저장값을
먼저 확인·변환한 뒤 ALTER TABLE로 변경해야 하며, 현재 SQL은 신규 DB 생성용입니다.

## 샘플

회원 5개·프로필 15개·약 2개·내 약 30개·대화방 45개·메시지 180개·요약 45개입니다.
사진·OCR 후보·DUR은 초기 행 없이 준비합니다. 품목 코드·약 이름은 공식 검증되지 않은 DB 확인용 샘플입니다.
실제 공공데이터 호출에는 공식 응답으로 확인한 품목 코드를 별도로 사용합니다.

검토·결정 기록은 개인 노션에 둡니다.
