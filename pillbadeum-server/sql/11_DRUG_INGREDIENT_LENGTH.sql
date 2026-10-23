-- CHK-09: 공식 복합 성분을 기존 30자에서 100자로 확장함. 원문01은 수정하지 않음.
-- 대상 DB에서 SQL01·03~10 적용 뒤 명시적으로 실행함. 기존 성분 값은 보존하며 재실행 가능함.
-- 이 패치는 이미 잘린 과거 문자열을 복원하지 않음. 이후 공식 정보 갱신으로 보강함.
-- TEXT를 적용한 DB에 과거 패치를 다시 실행해도 100자로 줄이지 않음.
SET @pill_ingredient_11 = (
    SELECT CASE WHEN DATA_TYPE IN ('text','mediumtext','longtext') OR CHARACTER_MAXIMUM_LENGTH >= 100
        THEN 'SELECT ''ingredient length already sufficient'' AS ingredient_11'
        ELSE 'ALTER TABLE drug MODIFY COLUMN material VARCHAR(100) NOT NULL COMMENT ''공식 성분명(최대 100자, 조용한 자르기 금지)'''
    END FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='material'
);
PREPARE pill_ingredient_11_stmt FROM @pill_ingredient_11;
EXECUTE pill_ingredient_11_stmt;
DEALLOCATE PREPARE pill_ingredient_11_stmt;

SELECT CASE WHEN CHARACTER_MAXIMUM_LENGTH >= 100 AND IS_NULLABLE = 'NO' THEN 'READY' ELSE 'CHECK_REQUIRED' END AS ingredient_patch_status
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'drug' AND column_name = 'material';
