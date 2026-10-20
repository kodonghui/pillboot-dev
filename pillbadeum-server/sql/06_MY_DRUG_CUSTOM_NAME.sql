-- 기존 DB용 수동 마이그레이션. 새 테이블을 만들거나 기존 약·일정 값을 수정하지 않습니다.
-- 대상 DB를 선택한 뒤 한 번만 실행하세요. 01번을 적용한 신규 DB에는 실행하지 않습니다.
-- item_seq FK는 유지합니다. NULL일 때만 약품 연결 없이 custom_name으로 표시합니다.
SELECT DATABASE() AS target_database;

ALTER TABLE `my_drug`
    MODIFY COLUMN `item_seq` VARCHAR(100) NULL DEFAULT NULL,
    ADD COLUMN `custom_name` VARCHAR(100) NULL DEFAULT NULL
        COMMENT '약품을 특정하지 않은 직접 입력 이름' AFTER `item_seq`,
    ADD CONSTRAINT chk_my_drug_identity CHECK (
        `item_seq` IS NOT NULL OR (`custom_name` IS NOT NULL AND CHAR_LENGTH(TRIM(`custom_name`)) > 0)
    );

-- my_drug_id, profile_id와 일정 컬럼은 그대로 유지합니다.
-- 직접 입력한 내 약의 source는 MANUAL입니다. source는 VARCHAR이므로 타입 변경은 없습니다.
