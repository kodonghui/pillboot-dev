# MySQL 설치와 기존 DB 갱신

- 앱 SQL은 00~14·16·17의 **17개**임. 새 설치 구조00·01, 선택 샘플02, 기존 구조 변경03~12·14·16·17, 기본 가입 약관13으로 구성함. 15(관계 값)는 SQL 변경이 필요 없어 비워 둠.
- 최신01은 23개 테이블·189개 컬럼과 승인된 제약을 포함함. 성분명은 material TEXT NOT NULL임. 새 DB는 구조 패치03~12·14·16·17을 거칠 필요가 없음.
- **신규 설치는00 → 01 → 13이며02는 선택임.** 약관은 샘플 계정과 분리되어13으로 준비함. 기존 DB는 누락된 구조 패치와13을 적용함.

## 처음 설치

서버 폴더에서 아래 명령 한 번을 실행함. 00으로 개발 DB/계정을 준비하고01로 구조,13으로 가입 약관을 생성함. 샘플 계정·약은 넣지 않음.

```powershell
.\scripts\setup-local-db.ps1
```

빈 개발 DB에 샘플이 필요할 때만 아래 명령을 사용함. 00·01·13 뒤02를 실행하며 실제 사용자 DB에는 사용하지 않음.

```powershell
.\scripts\setup-local-db.ps1 -IncludeTestData
```

- Workbench에서는00 → 대상 DB 선택 → 최신01 → 13 전체 실행임. Docker MySQL은 DB/계정 생성이 Compose에 있으므로01 → 13임. 필요할 때만 같은 버전의02를 한 번 실행함.
- 최신01은 복약/푸시·복합 UNIQUE·사진 연결·공식 메타데이터·복용량 확인·DUR 이력·벡터 정리·낱알 임시 결과·성분TEXT를 포함함.
- 02는 최신 컬럼명 relationship_type/material을 사용함. 옛 01과 최신 02를 섞지 않음. 실제 로그인 비밀번호·공식 품목 일치 검증용 자료가 아닌 화면/관계 fixture임.
- 13은 기존 프로젝트의 약관4종을 추가하며 회원·동의 이력은 생성하지 않음. 같은 terms_type이 있으면 본문·버전·활성 상태·ID를 보존함. 필수2종이 활성·시행 상태가 아니면 base_consent_status=BLOCKED임.

## 테스트 데이터를 버리고 다시 만들 때

- 테스트 데이터가 필요 없으면 DB를 삭제한 뒤 최신 구조로 재생성하면 됨. 00 맨 위에 초기화용 DROP을 기본 주석으로 제공함.

```sql
-- DROP DATABASE IF EXISTS pilldb;
```

- 초기화할 개발/테스트 DB인지 확인한 뒤 해당 한 줄만 주석 해제하여00 → 01 → 13 → 02(선택)를 실행함. 실행 후 DROP을 다시 주석 처리함. 기존 회원·프로필·약·일정·대화 데이터가 모두 삭제되는 방식임.
- 기본 설정에서는 DROP을 실행하지 않으며 앱 시작 시 DB를 자동 초기화하지 않음. Qdrant 데이터와 업로드 파일은 이 MySQL 초기화와 별도임.
- 데이터를 보존해야 하는 기존 DB에만 아래 갱신 경로를 사용함.

## 기존 데이터를 보존하며 갱신할 때

00~11을 모두 적용한 기존 DB는12 → 13 → 14 → 16만 대상 DB에서 실행함. ingredient_text_status·base_consent_status·drug_strength_status·chat_proposal_status의 READY를 확인함. 최신01을 다시 실행해 기존 컬럼이 바뀌기를 기대하지 않음.

일부 변경이 빠진 기존 팀 DB는 아래 명령으로03~12 → 13 → 14 → 16을 순서대로 적용함. 01·02는 실행하지 않음.

```powershell
.\scripts\setup-local-db.ps1 -UpgradeExisting
```

- 03~12는 이미 적용된 구조·확인 상태를 보존하도록 작성한 패치임. 11을 다시 실행해도 TEXT를100자로 줄이지 않음. 12는 기존 값을 보존하며 과거에 잘린 문자열을 자동 복원하지 않음. 변경 이력을 삭제하거나 기존 DB에 샘플02를 넣지 않음.
- 갱신 전 백업과 대상 DB를 확인함. DDL은 자동 커밋되므로 오류/BLOCKED를 무시하지 않음. 스크립트는 최종 current_schema_status=READY를 확인하며 실패 시 서버를 갱신하지 않음.
- 03의 필수 표/중복 알림 설정 사전 검사, 04의 기존 중복 검사에 실패하면 데이터를 임의 삭제하지 않음. 문제 행을 확인하고 담당자가 정리한 뒤 재실행함.
- 앱의 SQL 자동 실행·JPA 자동 DDL은 계속 꺼져 있음. 이 파일 정리는 사용자 DB를 자동 변경하지 않음.

## 파일별 역할

| 파일 | 용도 |
| --- | --- |
| `00_PILLBADEUM_DB_CREAT.sql` | 개발 DB·계정 준비임. 기존 계정 비밀번호는 바꾸지 않음. |
| `01_PILLBADEUM_DB_SCRIPT.sql` | 새 설치용 최신 전체 구조임. 23표·189열·PK23·FK32·UNIQUE10·CHECK15임. |
| `02_PILLBADEUM_TEST_DATA.sql` | 빈 개발 DB의 선택 샘플임. |
| `03_MEDICATION_UPDATE.sql` | 기존 DB의 컬럼명 오타·직접 입력 약·일정 NULL·푸시 구조 갱신임. |
| `04_MY_DRUG_COMPOSITE_UNIQUE.sql` | 동일 프로필·품목 중복 방지임. item_seq=NULL인 직접 입력 약은 허용함. |
| `05_PHOTO_CANDIDATE_LINKS.sql` | 사진 앞뒤 연결·공식 후보 품목코드임. |
| `06_DRUG_CACHE_METADATA.sql` | 공식 출처/갱신·nullable 취하 상태임. |
| `07_DOSE_CONFIRMATION.sql` | 복용량 확인 상태임. 처음 추가할 때만 기존 일정 유무로 분류하고 기존 수치/일정·재실행 확인 상태를 보존함. |
| `08_CHAT_DUR_SNAPSHOT.sql` | 답변 당시 DUR JSON임. 과거 답변은 NULL로 유지함. |
| `09_VECTOR_CLEANUP.sql` | Qdrant 정리 대기 표임. 외부 정리 성공 후 대기를 제거함. |
| `10_PILL_IDENTIFICATION_SESSION.sql` | 낱알 사진/단서 결과 30분 임시 보관임. |
| `11_DRUG_INGREDIENT_LENGTH.sql` | 기존 material 30자→100자 확장임. 기존 값을 자르지 않으며 과거 손실 문자열을 복원하지 않음. |
| `12_DRUG_INGREDIENT_TEXT.sql` | material을TEXT NOT NULL로 확장해100자를 넘는 공식 성분도 보존함. |
| `13_BASE_CONSENT.sql` | 가입 약관4종만 준비함. 기존 약관과 사용자 동의 선택을 보존함. |
| `14_DRUG_STRENGTH.sql` | V3-DRUG-001: drug에 기본 이름·함량 원문·μg 값·표시 단위 칸을 더함. 기존 행 값은 바꾸지 않고 빈 칸으로 둠. |
| `16_CHAT_PROPOSAL.sql` | V3-CHAT-002~005·007: 대화 속 등록 제안·일정 제안 표 3개(chat_proposal·chat_proposal_item·chat_proposal_schedule)를 더함. 기존 표·값은 바꾸지 않음. 등록 방법 값 `CHAT`은 my_drug.source varchar(30)에 들어가 구조 변경 없음. |
| `17_KAKAO_LINK.sql` | V3-KAKAO-012~016(DEC-029): 카톡 약봉이를 웹 회원에 잇는 연결 코드 표 `kakao_link_code`·연결 표 `kakao_link`를 더하고, chat_session에 연 곳 `channel`(WEB/KAKAO, 기본 WEB)을 더함. 기존 행은 모두 WEB으로 둠. 코드 원문·카카오 사용자 키 원문은 두지 않음(SHA-256·HMAC만). |

- 01의 CREATE TABLE IF NOT EXISTS는 같은 최신 구조 재실행에서 기존 값을 보존함. 기존 컬럼 변경·다른 10/15개 테이블 설계의 자동 이관 기능은 아님.
- 03~11의 순서와 파일명은 기존 적용 이력을 위해 유지함. 새 변경12·13·14·16·17을 뒤에 추가함.

## 16 제안 표의 규칙 (H-10)

- 제안을 상담 메시지 JSON이 아닌 별도 표에 둠. 만료·확정 상태와 중복 확정 방지를 DB 제약으로 지키기 위함임(도메인 H-10 추천을 TASK-021에서 정함).
- `chat_proposal`은 제안 하나임. 카드를 보여 주는 약봉이 메시지와 1:1(`uq_chat_proposal_message`)이고, 메시지·프로필·회원을 지우면 함께 지워짐(프로필 삭제는 약목록 약 → 상담 메시지 → 상담방 순으로 지우며 막히지 않음).
- 상태는 PENDING → CONFIRMED·CANCELLED·EXPIRED 한 방향임. 대기일 때만 `decided_at`이 NULL임(CHECK). 서버는 `status='PENDING' AND expires_at > 지금`인 행만 바꾸는 조건부 UPDATE로 상태를 바꾸고, 바뀐 행이 0이면 409로 답함. 동시에 두 번 확정하면 InnoDB 행 잠금 때문에 뒤 요청은 바뀐 행이 0임.
- `chat_proposal_item`은 등록 제안의 항목(최대 10개, 같은 품목 한 번)임. 확정으로 만든 약목록 약은 항목마다 하나(`uq_chat_proposal_item_my_drug`)이고, 같은 프로필·품목은 my_drug의 UNIQUE가 다시 막음. '이미 등록됨' 항목은 고를 수 없음(CHECK).
- 확정으로 만든 약을 약목록에서 지우면 항목의 `my_drug_id`만 NULL이 됨(ON DELETE SET NULL, DEC-019 ERD 묶음 Q3). 여러 개 삭제가 제안 기록 때문에 막히지 않음.
- `chat_proposal_schedule`은 일정 제안의 '지금'·'바뀐 뒤' 일정(JSON)과 대상 약임. 대상 약을 지우면 `my_drug_id`가 NULL이 되고 확정할 수 없음. JSON 칸 모양은 V3-CHAT-007 구현(TASK-029)에서 정함.

## 14 적용 뒤 함량 채우기 (V3-DRUG-002)

- 14는 칸만 더하고 기존 행의 함량은 비워 둠. 서버를 한 번 `--drug.strength-backfill.enabled=true`로 띄우면 빈 행을 500행씩 채우고 로그에 `filled`(값까지)·`textOnly`(원문만)·`none`(함량 없음) 수를 남김.
- 다시 실행하면 이미 채운 행은 건너뛰어 수가 모두 0임. 중간에 멈추면 다음 실행이 남은 행부터 함. 채우지 않은 행도 응답에서는 품목명에서 바로 뗀 값을 보여 줌.

```powershell
.\gradlew.bat bootRun --args='--drug.strength-backfill.enabled=true'
```

## 이전 검증 결과 · 2026-10-22 기준

- 최신01 단독 생성과 옛01+03~11의18개 테이블 구조가 같음을 확인함. 최신02 직접 입력, 기존 샘플 전체 보존,01 재실행 보존,07 확인 상태 재실행 보존, 기존DB의11 단독 확장을 확인함.03~11 파일은 미변경임.
- PowerShell7.6.7에서 신규 설치·선택 샘플·기존DB 갱신·충돌 옵션 차단을 실제 전용 MySQL 어댑터와 함께 검증함. Windows의 mysql.exe 자체 실행 검증은 아님.
- 새01만 사용하는 테스트 준비로 전체730개·84스위트·실패/오류/생략0·test/bootJar 성공임. [검증 집계](../docs/SQL_INSTALL_VALIDATION_2026_10_22.json)에 고정함. 실제 사용자DB와 팀원격은 변경하지 않음.

## 이번 변경 검증 · 2026-10-23

- 격리된 네이티브MySQL8.0.46에서 최신01의18표·146열, VARCHAR100→TEXT 기존 값 보존,416자 저장과11·12 재실행,13의 중복 방지·기존 약관 보존,13 뒤 선택02 실행을 확인함.
- 샘플 없이01·13만 준비한 실제 서버/브라우저에서 필수 약관 선택→가입→자동 로그인을 확인함. 01만 준비해 GET 약관이 빈 배열일 때는 안내·재시도만 표시하고 다음을 차단함.
- 사용자 업무DB에는 적용하지 않음. Docker·다른 팀원PC에서의 신규 설치는 별도 확인 대상임.
