# MySQL SQL 실행 순서

GitHub develop e7c5338의 기존 00~02번은 내용 그대로 유지합니다. 복약지도 변경은 03, 중복 방지는 04, 사진 후보 연결은 05로 분리함.

| 파일 | 용도 |
| --- | --- |
| `00_PILLBADEUM_DB_CREAT.sql` | 기존 DB·개발 계정 생성 스크립트 |
| `01_PILLBADEUM_DB_SCRIPT.sql` | 기존 ERD 원본 구조: 14개 테이블·114개 컬럼 |
| `02_PILLBADEUM_TEST_DATA.sql` | 선택 사항. 빈 원본 구조에 테스트 샘플 입력 |
| `03_MEDICATION_UPDATE.sql` | 복약지도 변경 통합. 기존 03~06번 대체 |
| `04_MY_DRUG_COMPOSITE_UNIQUE.sql` | 같은 프로필·공식 품목의 중복 등록 방지 |
| `05_PHOTO_CANDIDATE_LINKS.sql` | 앞뒤 사진 연결과 OCR 후보의 공식 품목코드 연결 |

## 새 DB

1. 관리자 연결에서 00 실행 후 대상 DB 선택.
2. 01 실행.
3. 샘플이 필요하면 빈 DB에서 02를 한 번 실행.
4. 03 전체를 같은 연결에서 실행하고 마지막 medication_update_status가 READY인지 확인.
5. 04 실행 후 마지막 my_drug_unique_status가 READY인지 확인.
6. 05 실행 후 마지막 photo_links_status가 READY인지 확인함.
7. 06 → 07 → 08 → 09를 순서대로 전체 실행하고 각 마지막 상태 READY를 확인함.
8. 최신 서버 실행함.

02는 원본 컬럼명을 쓰므로 03보다 먼저 실행합니다. 이미 샘플이 있는 DB에서 02를 다시 실행하지 않습니다.
03 적용 후 16개 테이블·125개 컬럼, 05 적용 후 16개 테이블·127개 컬럼·FK 18개임. 앱의 SQL 자동 실행과 JPA 자동 DDL은 꺼져 있습니다.
setup-local-db.ps1도 00 → 01 → 02(선택) → 03 → 04 → 05 → 06 → 07 → 08 → 09 순서로 실행합니다.

## 이미 사용 중인 DB

기존 원본 구조나 이전 변경을 적용한 DB는 **03 → 04 → 05 → 06 → 07 → 08 → 09 순서로 실행함**. 04까지 적용했다면 05만 실행함.
01·02를 다시 실행하거나 기존 데이터를 초기화할 필요가 없습니다. 03은 이미 적용된 컬럼·제약·표를 확인해 건너뜁니다.

통합 내용:

- profile.realationship_type → relationship_type, drug.matrial → material 오타 수정.
- my_drug.start_date/end_date/dose_times NULL 허용. 세 값을 함께 채우거나 비우도록 CHECK 유지.
- my_drug.item_seq NULL 허용과 custom_name VARCHAR(100) NULL 추가. 약품 코드 또는 직접 입력 이름 필수 CHECK.
- notification_setting.member_id 회원당 한 행 UNIQUE.
- push_subscription 브라우저 구독과 notification_dispatch 분 단위 중복 발송 방지 표 추가.
- MANUAL은 기존 source VARCHAR(30)에 저장하므로 타입 변경이 없습니다.

업무 데이터 INSERT·UPDATE·DELETE, 테이블 DROP은 포함하지 않습니다. item_seq FK도 유지합니다.
MySQL 8.0.16 이상에서 대상 DB를 선택한 뒤 파일 전체를 같은 연결에서 실행합니다. DDL은 자동 커밋되므로 중간 SQL 오류를 무시하지 않습니다.

## BLOCKED가 나올 때

사전 검사에서 필수 원본 표가 없거나, 이름 변경 전·후 컬럼이 함께 있거나 모두 없거나, 회원당 알림 설정이 중복되면 모든 DDL을 건너뛰고 BLOCKED를 표시합니다.
출력의 required_tables_present / profile_column_unambiguous / drug_column_unambiguous / duplicate_notification_members를 확인합니다.
알림 설정 중복은 아래 조회로 확인하고 어떤 행을 남길지 정리한 뒤 03을 재실행합니다. 통합 SQL이 임의로 삭제하지 않습니다.

```sql
SELECT member_id, COUNT(*) AS setting_rows
FROM notification_setting
GROUP BY member_id
HAVING COUNT(*) > 1;
```

이 파일은 원본 14개 테이블 이후의 복약지도 변경용입니다. 이전 별도 설계의 10개/15개 테이블 DB 변환이나 팀원의 다른 데이터 이관은 다루지 않습니다.
원본 샘플은 공식 품목 코드·계정 비밀번호 검증 자료가 아닙니다. Qdrant는 MySQL 생성 대상이 아닙니다.

04는 `my_drug(profile_id,item_seq)` 복합 UNIQUE를 추가합니다. `item_seq=NULL`인 직접 입력 약은 허용합니다. 기존 중복이나 충돌하는 제약이 있으면 데이터를 삭제하지 않고 BLOCKED를 표시합니다.


## 사진 후보 연결 패치 05

사용자가 REG-003~007 전체 흐름을 위해 승인한 추가임. 원본 01의 누락 열을 임의 수정하지 않고 다음 두 열만 추가함.

- `photo.front_photo_id BIGINT NULL`: 뒷면이 대표 앞면을 참조함. 같은 그룹 삭제 시 앞뒤를 함께 정리함.
- `ocr_candidate.item_seq VARCHAR(100) NULL`: 후보가 실제 `drug.item_seq`를 참조함. 실패·0건 및 기존 키워드 행은 NULL임.

기존 파일 경로·키워드·약 등록 행은 보존함. 데이터 INSERT/UPDATE/DELETE는 없으며 재실행해도 열·FK를 중복 생성하지 않음. 마지막 `photo_links_status=READY`가 필수임. DDL 오류나 BLOCKED이면 서버 실행을 멈추고 원인을 확인함. 기존 후보의 NULL을 임의 공식 코드로 채우지 않음.

서버가 업무 DB에 자동 적용하지 않음. 실제 실행 DB에서 05를 명시적으로 적용한 뒤 서버를 갱신함. 사진 파일은 SQL로 삭제되지 않으며 등록 확정·나가기·실패 및 30분 만료(기본 10분 간격)에서 서버가 정리함.

## 2026-10-21 버그 수정용 06~09 패치

- `06_DRUG_CACHE_METADATA.sql`: 공식 메타데이터 출처와 nullable 취하 상태를 저장함. 기존 출처는 UNKNOWN으로 남김.
- `07_DOSE_CONFIRMATION.sql`: 복용량 확인 상태를 추가함. 최초 적용 시 일정이 있는 행은 확인됨, 없는 행은 미확정으로 분류함. 기존 숫자·기간·시각은 보존하며 재실행으로 확인 상태를 덮지 않음. 과거 일정 없는 행의 숫자가 실제 입력값인지 알 수 없어 사용자 재확인이 필요한 한계임.
- `08_CHAT_DUR_SNAPSHOT.sql`: 답변 당시 DUR JSON을 보존함. 기존 답변은 NULL이며 현재 상태로 과거 경고를 소급 생성하지 않음.
- `09_VECTOR_CLEANUP.sql`: 프로필 삭제 후 Qdrant 정리 대기 표를 추가함. 방 ID·요청 시각만 보존하고 외부 정리 완료 뒤 제거함. Qdrant off/장애 동안 대기하며 기본60초마다 최대50개를 재시도함.
- 최종 구조는17개 표·133개 열·PK17·FK18·UNIQUE4·CHECK3임. 사용자 DB는 자동 변경하지 않음. 운영/공용 DB에서는 백업 후 대상 DB와 패치 결과를 확인함.
- 새 API에서 미확정 `dosePerTime`은 null, 과거 메시지의 `durNotice`는 nullable임. 사진 카드 `lookup=PARTIAL`은 정상 후보를 유지하면서 불완전한 행이 제외되었음을 의미함.
