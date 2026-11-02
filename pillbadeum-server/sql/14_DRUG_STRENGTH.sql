-- V3-DRUG-001: 품목명은 그대로 두고 기본 이름·함량 원문·비교용 μg 값·표시 단위를 따로 보존함.
-- 기존 DB에서 13 뒤 실행함. 최신01로 만든 DB에는 이미 같은 구조가 있음. 기존 행 값은 바꾸지 않음.
-- 빈 칸은 서버의 함량 채우기 실행기(V3-DRUG-002)가 채움. 다시 실행해도 같은 결과임.
SET @drug_base_name_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='base_name')=0,
    'ALTER TABLE drug ADD COLUMN base_name VARCHAR(50) NULL COMMENT ''함량·괄호 성분명을 뗀 기본 이름''', 'SELECT 1');
PREPARE drug_base_name_stmt FROM @drug_base_name_ddl;
EXECUTE drug_base_name_stmt;
DEALLOCATE PREPARE drug_base_name_stmt;

SET @drug_strength_text_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='strength_text')=0,
    'ALTER TABLE drug ADD COLUMN strength_text VARCHAR(50) NULL COMMENT ''품목명의 함량 원문''', 'SELECT 1');
PREPARE drug_strength_text_stmt FROM @drug_strength_text_ddl;
EXECUTE drug_strength_text_stmt;
DEALLOCATE PREPARE drug_strength_text_stmt;

SET @drug_strength_ug_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='strength_ug')=0,
    'ALTER TABLE drug ADD COLUMN strength_ug DECIMAL(20,4) NULL COMMENT ''단일 성분 질량 함량의 μg 값''', 'SELECT 1');
PREPARE drug_strength_ug_stmt FROM @drug_strength_ug_ddl;
EXECUTE drug_strength_ug_stmt;
DEALLOCATE PREPARE drug_strength_ug_stmt;

SET @drug_strength_unit_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='strength_unit')=0,
    'ALTER TABLE drug ADD COLUMN strength_unit VARCHAR(10) NULL COMMENT ''함량 표시 단위(mg·μg·g)''', 'SELECT 1');
PREPARE drug_strength_unit_stmt FROM @drug_strength_unit_ddl;
EXECUTE drug_strength_unit_stmt;
DEALLOCATE PREPARE drug_strength_unit_stmt;

SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drug'
        AND column_name='base_name' AND data_type='varchar' AND character_maximum_length=50 AND is_nullable='YES')=1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drug'
        AND column_name='strength_text' AND data_type='varchar' AND character_maximum_length=50 AND is_nullable='YES')=1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drug'
        AND column_name='strength_ug' AND data_type='decimal' AND numeric_precision=20 AND numeric_scale=4 AND is_nullable='YES')=1
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drug'
        AND column_name='strength_unit' AND data_type='varchar' AND character_maximum_length=10 AND is_nullable='YES')=1,
    'READY','BLOCKED') AS drug_strength_status;
