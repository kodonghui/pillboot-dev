-- 2026-11-08 약봉이 카톡 개선(PM 결정 'P0+P1 한꺼번에', 카톡 기록 진단): 처방전 줄에서 읽은 1회 투약량·1일 투여 횟수·
-- 총 투약 일수를 등록 제안 항목에 두고(확정 때 '처방대로 일정 넣기'에 씀), 일정 제안 하나에 약 여러 개(최대 10)를 담음.
-- 기존 DB에서 17 뒤 실행함. 최신01로 만든 DB에는 이미 같은 구조가 있음. 기존 행·값은 바꾸지 않고(기존 일정 제안은 1번 줄),
-- 다시 실행해도 같은 결과임. 16의 확인 쿼리는 18 전·뒤 칸 수를 모두 받고, 18이 더한 구조는 이 파일의 확인 쿼리로 봄.

-- 등록 제안 항목: 처방전에서 읽은 값(사진 제안만, 글 제안은 NULL)과 확정 때 처방대로 일정을 넣었는지.
SET @chat_item_rx_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='chat_proposal_item' AND column_name='rx_dose')=0,
    'ALTER TABLE chat_proposal_item
        ADD COLUMN rx_dose VARCHAR(10) NULL COMMENT ''처방전의 1회 투약량(인쇄된 그대로, 예 1·0.5)'' AFTER my_drug_id,
        ADD COLUMN rx_daily_count TINYINT UNSIGNED NULL COMMENT ''처방전의 1일 투여 횟수'' AFTER rx_dose,
        ADD COLUMN rx_days SMALLINT UNSIGNED NULL COMMENT ''처방전의 총 투약 일수'' AFTER rx_daily_count,
        ADD COLUMN rx_applied TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''확정 때 처방대로 일정을 넣었음'' AFTER rx_days',
    'SELECT 1');
PREPARE chat_item_rx_stmt FROM @chat_item_rx_ddl;
EXECUTE chat_item_rx_stmt;
DEALLOCATE PREPARE chat_item_rx_stmt;

-- 일정 제안: 약마다 한 줄(카드 안 순서 1~10)과 확정 전에 볼 점. 확정은 모든 줄을 한 트랜잭션으로 저장함.
SET @chat_schedule_item_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='chat_proposal_schedule' AND column_name='item_no')=0,
    'ALTER TABLE chat_proposal_schedule
        ADD COLUMN item_no TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT ''카드 안 순서 1~10'' AFTER proposal_id,
        ADD COLUMN note VARCHAR(200) NULL COMMENT ''확정 전에 볼 점(처방과 다름·횟수 바뀜 등)'' AFTER proposed_schedule,
        DROP PRIMARY KEY, ADD PRIMARY KEY (proposal_id, item_no)',
    'SELECT 1');
PREPARE chat_schedule_item_stmt FROM @chat_schedule_item_ddl;
EXECUTE chat_schedule_item_stmt;
DEALLOCATE PREPARE chat_schedule_item_stmt;

SET @chat_schedule_item_check = IF((SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema=DATABASE() AND table_name='chat_proposal_schedule'
    AND constraint_name='ck_chat_proposal_schedule_item_no')=0,
    'ALTER TABLE chat_proposal_schedule ADD CONSTRAINT ck_chat_proposal_schedule_item_no CHECK (item_no BETWEEN 1 AND 10)',
    'SELECT 1');
PREPARE chat_schedule_item_check_stmt FROM @chat_schedule_item_check;
EXECUTE chat_schedule_item_check_stmt;
DEALLOCATE PREPARE chat_schedule_item_check_stmt;

SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_proposal_item')=12
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_proposal_schedule')=6
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_proposal_item'
        AND column_name='rx_applied' AND is_nullable='NO' AND column_default='0')=1
    AND (SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.key_column_usage
        WHERE constraint_schema=DATABASE() AND table_name='chat_proposal_schedule' AND constraint_name='PRIMARY')
        = 'proposal_id,item_no'
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name='chat_proposal_schedule' AND constraint_name='ck_chat_proposal_schedule_item_no'
        AND constraint_type='CHECK' AND enforced='YES')=1
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name='chat_proposal_schedule' AND constraint_type='FOREIGN KEY')=2,
    'READY','BLOCKED') AS chat_proposal_prescription_status;
