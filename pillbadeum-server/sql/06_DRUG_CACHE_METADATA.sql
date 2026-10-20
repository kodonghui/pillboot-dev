-- BUG-006/009: 실제 메타데이터 출처와 공식 취하 상태를 보존함. 05 뒤 명시 적용하며 원본 행은 삭제하지 않음.
SET @drug_source_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='metadata_source')=0,
    'ALTER TABLE drug ADD COLUMN metadata_source VARCHAR(20) NOT NULL DEFAULT ''UNKNOWN''', 'SELECT 1');
PREPARE drug_source_stmt FROM @drug_source_ddl;
EXECUTE drug_source_stmt;
DEALLOCATE PREPARE drug_source_stmt;

SET @drug_cancel_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='cancelled')=0,
    'ALTER TABLE drug ADD COLUMN cancelled BOOLEAN NULL', 'SELECT 1');
PREPARE drug_cancel_stmt FROM @drug_cancel_ddl;
EXECUTE drug_cancel_stmt;
DEALLOCATE PREPARE drug_cancel_stmt;

SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drug'
        AND column_name='metadata_source' AND data_type='varchar' AND character_maximum_length=20 AND is_nullable='NO')=1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drug'
        AND column_name='cancelled' AND data_type='tinyint' AND is_nullable='YES')=1,
    'READY','BLOCKED') AS drug_metadata_status;
