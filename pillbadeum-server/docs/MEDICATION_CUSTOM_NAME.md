# 약품 연결 없는 복약 일정

2026-10-20 사용자 결정: 새 테이블 없이 `my_drug.item_seq`의 NULL 허용과 `custom_name VARCHAR(100) NULL` 추가로 지원한다.

- 식별된 약: 기존 item_seq와 drug.name을 그대로 사용한다.
- 직접 입력한 약: item_seq=NULL, custom_name=실제 말한 이름, source=MANUAL이다. 약품 코드 또는 직접 입력 이름 중 하나는 반드시 있어야 한다.
- profile_id와 자동 발급 my_drug_id는 필수이며 기존 일정 컬럼·FK·API 경로를 유지한다.
- 기존 DB는 대상 DB를 선택한 뒤 `sql/03_MEDICATION_UPDATE.sql` 전체를 실행한다. 새 DB는 원본 01 → 02(샘플 선택) → 03 순서다. 이미 적용한 항목은 건너뛰며 앱이 자동 적용하지 않는다.

## Live 테스트

1. 통합 03 SQL을 적용하고 서버를 재시작한다. `/dev/medication-live`를 새로고침한다.
2. 로그인 JWT와 본인 프로필 ID·선택 날짜를 넣는다. 내 약 ID는 비운다. 등록된 약이 없는 프로필도 가능하다.
3. “오늘부터 일주일 혈압약”이라고 말한다. 하루 3번 07:00·11:00·17:00, 1회 1개, 식후 30분 제안을 받고 “응”이라고 한다.
4. `SAVED` 응답의 `savedSchedule.myDrugId`와 `completedOperation.myDrugId`가 실제 새 ID이며 drugName이 혈압약인지 확인한다.
5. 기존 일정 상세·캘린더·날짜별 목록에서 이름이 표시되는지 확인한다.
6. “혈압약 일정 삭제해줘” 또는 “혈압약 그만 먹을래”를 각각 별도 일정으로 확인한다. 삭제는 일정 컬럼 세 개만 NULL, 그만 먹기는 오늘까지 표시한다. 직접 입력 이름·약 행은 남는다.
7. 별도로 내 약 ID를 지정하고 이름을 생략해 기존 약 일정 변경이 유지되는지 확인한다.
8. 제안 뒤 “취소해”라고 하거나 세션을 종료하면 새 약 행이 생성되지 않아야 한다. 약 이름도 생략하면 임의 이름으로 저장하지 않고 질문해야 한다.

SQL 확인은 완료 응답으로 받은 ID를 사용한다.

```sql
SELECT my_drug_id, profile_id, item_seq, custom_name, source,
       start_date, end_date, dose_times, dose_per_time, timing
FROM my_drug
WHERE my_drug_id = /* 완료 응답 myDrugId */ 38;
```

자동 검증은 실제 OpenAI를 호출하지 않는다. Java 서비스·HTTP 검증과 Node 브라우저 모의 테스트가 있으며, MySQL 커밋·조회·수정·삭제 검증은 MYSQL_TEST_URL이 설정된 전용 pill_test에서만 실행한다. 실제 음성 인식·과금 연결과 기존 DB 마이그레이션 실행은 별도 확인 대상이다.
