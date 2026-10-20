-- REG-003~007 전체 흐름을 위한 사용자 승인 추가 패치임. 01→03→04 뒤 명시적으로 적용함.
-- 기존 사진/키워드 행은 보존하고 연결 값은 NULL로 둠. 마지막 photo_links_status=READY를 확인함.
SET @photo_front_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='photo' AND column_name='front_photo_id')=0,
    'ALTER TABLE photo ADD COLUMN front_photo_id BIGINT NULL AFTER side', 'SELECT 1');
PREPARE photo_front_stmt FROM @photo_front_ddl;
EXECUTE photo_front_stmt;
DEALLOCATE PREPARE photo_front_stmt;

SET @candidate_item_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='ocr_candidate' AND column_name='item_seq')=0,
    'ALTER TABLE ocr_candidate ADD COLUMN item_seq VARCHAR(100) NULL AFTER read_name', 'SELECT 1');
PREPARE candidate_item_stmt FROM @candidate_item_ddl;
EXECUTE candidate_item_stmt;
DEALLOCATE PREPARE candidate_item_stmt;

SET @photo_front_fk = IF((SELECT COUNT(*) FROM information_schema.key_column_usage
    WHERE constraint_schema=DATABASE() AND table_name='photo' AND column_name='front_photo_id'
    AND referenced_table_name='photo' AND referenced_column_name='photo_id')=0,
    'ALTER TABLE photo ADD CONSTRAINT fk_photo_front FOREIGN KEY(front_photo_id) REFERENCES photo(photo_id) ON DELETE CASCADE', 'SELECT 1');
PREPARE photo_front_fk_stmt FROM @photo_front_fk;
EXECUTE photo_front_fk_stmt;
DEALLOCATE PREPARE photo_front_fk_stmt;

SET @candidate_item_fk = IF((SELECT COUNT(*) FROM information_schema.key_column_usage
    WHERE constraint_schema=DATABASE() AND table_name='ocr_candidate' AND column_name='item_seq'
    AND referenced_table_name='drug' AND referenced_column_name='item_seq')=0,
    'ALTER TABLE ocr_candidate ADD CONSTRAINT fk_ocr_candidate_drug FOREIGN KEY(item_seq) REFERENCES drug(item_seq)', 'SELECT 1');
PREPARE candidate_item_fk_stmt FROM @candidate_item_fk;
EXECUTE candidate_item_fk_stmt;
DEALLOCATE PREPARE candidate_item_fk_stmt;

SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='photo'
        AND column_name='front_photo_id' AND column_type='bigint' AND is_nullable='YES')=1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ocr_candidate'
        AND column_name='item_seq' AND data_type='varchar' AND character_maximum_length=100 AND is_nullable='YES')=1
    AND (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()
        AND ((table_name='photo' AND column_name='front_photo_id' AND referenced_table_name='photo' AND referenced_column_name='photo_id')
        OR (table_name='ocr_candidate' AND column_name='item_seq' AND referenced_table_name='drug' AND referenced_column_name='item_seq')))=2,
    'READY','BLOCKED') AS photo_links_status;
