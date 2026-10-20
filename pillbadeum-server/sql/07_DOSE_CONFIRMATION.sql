-- BUG-004: DB의 초기값1과 사용자가 확인한 복용량을 구분함. 원래 수치/일정은 바꾸지 않음.
-- 06 뒤 명시 적용함. 처음 추가할 때만 기존 일정 없는 행을 미확정으로 분류하며 재실행은 확인 상태를 보존함.
SET @dose_flag_new = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='my_drug' AND column_name='dose_confirmed')=0;
SET @dose_flag_ddl = IF(@dose_flag_new,
    'ALTER TABLE my_drug ADD COLUMN dose_confirmed BOOLEAN NOT NULL DEFAULT TRUE', 'SELECT 1');
PREPARE dose_flag_stmt FROM @dose_flag_ddl;
EXECUTE dose_flag_stmt;
DEALLOCATE PREPARE dose_flag_stmt;
SET @dose_flag_backfill = IF(@dose_flag_new,
    'UPDATE my_drug SET dose_confirmed=(start_date IS NOT NULL AND end_date IS NOT NULL AND dose_times IS NOT NULL)', 'SELECT 1');
PREPARE dose_flag_data_stmt FROM @dose_flag_backfill;
EXECUTE dose_flag_data_stmt;
DEALLOCATE PREPARE dose_flag_data_stmt;
SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='my_drug'
    AND column_name='dose_confirmed' AND data_type='tinyint' AND is_nullable='NO')=1,'READY','BLOCKED') AS dose_confirmation_status;
