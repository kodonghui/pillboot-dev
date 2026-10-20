# MySQL SQL 실행 순서

GitHub develop e7c5338의 기존 00~02번은 내용 그대로 유지합니다. 복약지도에서 추가한 변경만 03번 하나에 모았습니다.

| 파일 | 용도 |
| --- | --- |
| `00_PILLBADEUM_DB_CREAT.sql` | 기존 DB·개발 계정 생성 스크립트 |
| `01_PILLBADEUM_DB_SCRIPT.sql` | 기존 ERD 원본 구조: 14개 테이블·114개 컬럼 |
| `02_PILLBADEUM_TEST_DATA.sql` | 선택 사항. 빈 원본 구조에 테스트 샘플 입력 |
| `03_MEDICATION_UPDATE.sql` | 복약지도 변경 통합. 기존 03~06번 대체 |
| `04_MY_DRUG_COMPOSITE_UNIQUE.sql` | 같은 프로필·공식 품목의 중복 등록 방지 |

## 새 DB

1. 관리자 연결에서 00 실행 후 대상 DB 선택.
2. 01 실행.
3. 샘플이 필요하면 빈 DB에서 02를 한 번 실행.
4. 03 전체를 같은 연결에서 실행하고 마지막 medication_update_status가 READY인지 확인.
5. 04 실행 후 마지막 my_drug_unique_status가 READY인지 확인.
6. 최신 서버 실행.

02는 원본 컬럼명을 쓰므로 03보다 먼저 실행합니다. 이미 샘플이 있는 DB에서 02를 다시 실행하지 않습니다.
03 적용 후 구조는 16개 테이블·125개 컬럼입니다. 앱의 SQL 자동 실행과 JPA 자동 DDL은 꺼져 있습니다.
setup-local-db.ps1도 00 → 01 → 02(선택) → 03 → 04 순서로 실행합니다.

## 이미 사용 중인 DB

기존 원본 구조나 이전 변경을 적용한 DB는 **03 → 04 순서로 실행**합니다. 03 적용을 완료했다면 04만 실행합니다.
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
