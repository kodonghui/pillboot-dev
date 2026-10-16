# MySQL SQL 실행 순서

원본 `-server`의 10개 테이블 정의와 샘플 데이터를 유지하면서 삭제 구문을 제거하고 파일을 나눴습니다.

| 파일 | 실행 목적 |
| --- | --- |
| `00_PILLBADEUM_DB_CREAT.sql` | 관리자 연결에서 로컬 `pilldb`·개발 계정 준비 |
| `01_PILLBADEUM_DB_SCRIPT.sql` | 선택한 DB에 없는 테이블 생성. 기존 데이터 보존 |
| `02_PILLBADEUM_TEST_DATA.sql` | 빈 개발 DB에 한 번만 샘플 입력. 선택 실행 |

`01`과 `02`는 DB 이름을 강제하지 않습니다. Workbench 또는 클라이언트에서 대상 DB를 먼저 선택하세요.
앱은 이 SQL을 자동 실행하지 않습니다. `01`을 다시 실행해도 기존 스키마를 변경하지 않으므로
테이블 정의가 다른 기존 DB의 변경은 별도로 검토해야 합니다.

테이블은 `member`, `terms`, `profile`, `consent_history`, `notification_setting`, `drug`,
`my_drug`, `chat_session`, `chat_message`, `chat_summary`입니다.

기존 계정의 비밀번호는 변경하지 않습니다. 접속이 거절되면 기존 계정의 비밀번호·host·권한을 확인하세요.
테스트는 별도 `pill_test` DB에서만 실행하며 개발용 `pilldb`를 사용하지 않습니다.
