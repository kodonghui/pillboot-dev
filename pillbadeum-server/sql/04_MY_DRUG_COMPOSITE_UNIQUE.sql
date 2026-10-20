-- 같은 프로필 + 같은 공식 품목코드의 중복만 막습니다. 01 → 03 → 04 순서로 적용합니다.
-- MySQL 8.0.16 이상. 대상 DB를 선택한 같은 연결에서 파일 전체를 실행합니다.
-- 업무 행을 삭제/변경하지 않습니다. 중복·잘못된 기존 UNIQUE가 있으면 BLOCKED로 종료합니다.
-- 직접 입력 약(item_seq IS NULL)은 이 제약의 중복 판정 대상이 아닙니다.
SELECT DATABASE() AS target_database;
SET @my_drug_unique_columns_ready = (
    SELECT COUNT(*) = 2 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='my_drug' AND column_name IN ('profile_id','item_seq')
);
SET @my_drug_unique_existing = EXISTS (
    SELECT index_name FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='my_drug' AND non_unique=0
    GROUP BY index_name
    HAVING COUNT(*)=2 AND SUM(column_name IN ('profile_id','item_seq'))=2
        AND SUM(sub_part IS NOT NULL)=0
);
SET @my_drug_unique_single_column = EXISTS (
    SELECT index_name FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='my_drug' AND non_unique=0
    GROUP BY index_name HAVING COUNT(*)=1 AND MIN(column_name) IN ('profile_id','item_seq')
);
SET @my_drug_unique_name_conflict = NOT @my_drug_unique_existing AND EXISTS (
    SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='my_drug' AND index_name='uk_my_drug_profile_item'
);
SET @my_drug_unique_sql = IF(@my_drug_unique_columns_ready,
    'SELECT COUNT(*) INTO @my_drug_unique_duplicates FROM (SELECT profile_id,item_seq FROM my_drug WHERE item_seq IS NOT NULL GROUP BY profile_id,item_seq HAVING COUNT(*)>1) duplicate_pairs',
    'SET @my_drug_unique_duplicates=0');
PREPARE my_drug_unique_stmt FROM @my_drug_unique_sql;
EXECUTE my_drug_unique_stmt;
DEALLOCATE PREPARE my_drug_unique_stmt;
SET @my_drug_unique_ready = DATABASE() IS NOT NULL AND @my_drug_unique_columns_ready
    AND @my_drug_unique_duplicates=0 AND NOT @my_drug_unique_single_column
    AND NOT @my_drug_unique_name_conflict;
SELECT IF(@my_drug_unique_ready,'READY','BLOCKED') AS precheck,
    @my_drug_unique_duplicates AS duplicate_pair_groups,
    @my_drug_unique_single_column AS individual_unique_found,
    @my_drug_unique_name_conflict AS index_name_conflict;
SET @my_drug_unique_sql = IF(@my_drug_unique_ready AND NOT @my_drug_unique_existing,
    'ALTER TABLE `my_drug` ADD CONSTRAINT uk_my_drug_profile_item UNIQUE (`profile_id`,`item_seq`)',
    'DO 0');
PREPARE my_drug_unique_stmt FROM @my_drug_unique_sql;
EXECUTE my_drug_unique_stmt;
DEALLOCATE PREPARE my_drug_unique_stmt;
SELECT IF(@my_drug_unique_ready AND EXISTS (
    SELECT index_name FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='my_drug' AND non_unique=0
    GROUP BY index_name HAVING COUNT(*)=2 AND SUM(column_name IN ('profile_id','item_seq'))=2
        AND SUM(sub_part IS NOT NULL)=0
),'READY','BLOCKED') AS my_drug_unique_status;
