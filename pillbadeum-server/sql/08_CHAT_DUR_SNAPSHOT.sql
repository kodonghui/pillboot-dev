-- BUG-008: 답변 당시의 DUR 카드 구조를 보존함. 기존 원문은 바꾸지 않고 과거 행은 NULL로 유지함.
SET @dur_snapshot_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='chat_message' AND column_name='dur_notice')=0,
    'ALTER TABLE chat_message ADD COLUMN dur_notice JSON NULL', 'SELECT 1');
PREPARE dur_snapshot_stmt FROM @dur_snapshot_ddl;
EXECUTE dur_snapshot_stmt;
DEALLOCATE PREPARE dur_snapshot_stmt;
SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_message'
    AND column_name='dur_notice' AND data_type='json' AND is_nullable='YES')=1,'READY','BLOCKED') AS chat_dur_snapshot_status;
