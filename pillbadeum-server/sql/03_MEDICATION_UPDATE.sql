-- 복약지도 추가 변경 통합본: GitHub develop e7c5338의 기존 00~02번 다음에 실행합니다.
-- 원래 있던 회원·프로필·약·대화 테이블 생성과 샘플 데이터는 포함하지 않습니다.
-- 기존 03~06번을 대체합니다. 일부 또는 전체 적용된 DB에도 실행할 수 있습니다.
-- MySQL 8.0.16 이상. 대상 DB를 선택하고 파일 전체를 같은 연결에서 실행하세요.
-- DDL은 자동 커밋됩니다. 업무 데이터 INSERT/UPDATE/DELETE와 테이블 DROP은 하지 않습니다.
-- 마지막 결과가 READY여야 완료입니다. BLOCKED면 표시된 원인을 정리한 뒤 다시 실행하세요.

SELECT DATABASE() AS target_database;
SET @medication_schema_ready = (
    SELECT COUNT(*) = 5 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name IN ('member','profile','drug','my_drug','notification_setting')
);
SET @medication_profile_name_ready = (
    SELECT COUNT(*) = 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name='profile' AND column_name IN ('realationship_type','relationship_type')
);
SET @medication_drug_name_ready = (
    SELECT COUNT(*) = 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name='drug' AND column_name IN ('matrial','material')
);
-- 중복 알림 설정을 임의 삭제하지 않습니다. 중복이 있으면 아래 모든 변경을 건너뜁니다.
SET @medication_sql = IF(@medication_schema_ready,
    'SELECT COUNT(*) INTO @medication_duplicate_settings FROM (SELECT member_id FROM notification_setting GROUP BY member_id HAVING COUNT(*) > 1) duplicate_members',
    'SET @medication_duplicate_settings = 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;
SET @medication_ready = DATABASE() IS NOT NULL AND @medication_schema_ready
    AND @medication_profile_name_ready AND @medication_drug_name_ready AND @medication_duplicate_settings=0;
SELECT IF(@medication_ready, 'READY', 'BLOCKED') AS precheck,
       @medication_duplicate_settings AS duplicate_notification_members,
       @medication_schema_ready AS required_tables_present,
       @medication_profile_name_ready AS profile_column_unambiguous,
       @medication_drug_name_ready AS drug_column_unambiguous;

-- 1. 컬럼명 오타 수정. 이미 바른 이름이면 건너뜁니다.
SET @medication_sql = IF(@medication_ready AND (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='profile' AND column_name='realationship_type')),
    'ALTER TABLE `profile` RENAME COLUMN `realationship_type` TO `relationship_type`',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 2. 약 성분 컬럼명 오타 수정.
SET @medication_sql = IF(@medication_ready AND (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='matrial')),
    'ALTER TABLE `drug` RENAME COLUMN `matrial` TO `material`',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 3. 일정 없음과 약품 미지정 허용. 기존 값과 item_seq FK는 유지합니다.
SET @medication_sql = IF(@medication_ready AND (EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='my_drug' AND column_name IN ('start_date','end_date','dose_times','item_seq') AND (is_nullable='NO' OR column_default IS NOT NULL))),
    'ALTER TABLE `my_drug`
    MODIFY COLUMN `start_date` DATE NULL DEFAULT NULL COMMENT ''복용 시작 날짜, 일정 없으면 NULL'',
    MODIFY COLUMN `end_date` DATE NULL DEFAULT NULL COMMENT ''복용 종료 날짜, 일정 등록 시 필수'',
    MODIFY COLUMN `dose_times` VARCHAR(50) NULL DEFAULT NULL COMMENT ''하루 1~3개 복용 시각, 일정 없으면 NULL'',
    MODIFY COLUMN `item_seq` VARCHAR(100) NULL DEFAULT NULL',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 4. 직접 입력 약 이름. my_drug_id와 profile_id는 여전히 필수입니다.
SET @medication_sql = IF(@medication_ready AND (NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='my_drug' AND column_name='custom_name')),
    'ALTER TABLE `my_drug` ADD COLUMN `custom_name` VARCHAR(100) NULL DEFAULT NULL COMMENT ''약품을 특정하지 않은 직접 입력 이름'' AFTER `item_seq`',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 5. 일정 세 항목은 모두 비우거나 모두 채웁니다.
SET @medication_sql = IF(@medication_ready AND (NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='my_drug' AND constraint_name='chk_my_drug_schedule_presence')),
    'ALTER TABLE `my_drug` ADD CONSTRAINT chk_my_drug_schedule_presence CHECK (
    (`start_date` IS NULL AND `end_date` IS NULL AND `dose_times` IS NULL)
    OR (`start_date` IS NOT NULL AND `end_date` IS NOT NULL AND `dose_times` IS NOT NULL)
)',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 6. 약품 코드 또는 직접 입력 이름 중 하나는 있어야 합니다.
SET @medication_sql = IF(@medication_ready AND (NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='my_drug' AND constraint_name='chk_my_drug_identity')),
    'ALTER TABLE `my_drug` ADD CONSTRAINT chk_my_drug_identity CHECK (
    `item_seq` IS NOT NULL OR (`custom_name` IS NOT NULL AND CHAR_LENGTH(TRIM(`custom_name`)) > 0)
)',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 7. 회원당 알림 설정은 한 행. 기존 동등한 UNIQUE도 인정합니다.
SET @medication_sql = IF(@medication_ready AND (NOT EXISTS (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='notification_setting' AND non_unique=0 GROUP BY index_name HAVING COUNT(*)=1 AND MIN(column_name)='member_id')),
    'ALTER TABLE `notification_setting` ADD CONSTRAINT uk_notification_setting_member UNIQUE (`member_id`)',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 8. 브라우저 푸시 구독 테이블. 기존 행을 유지합니다.
SET @medication_sql = IF(@medication_ready AND (TRUE),
    'CREATE TABLE IF NOT EXISTS `push_subscription` (
	`subscription_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT ''브라우저 푸시 구독 코드'',
	`member_id`	BIGINT	NOT NULL	COMMENT ''회원 코드, 이 브라우저로 마지막에 알림을 켠 회원'',
	`endpoint`	VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT ''브라우저 푸시 주소(UK), 대소문자 구분'',
	`p256dh`	VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT ''브라우저 공개 키(base64url)'',
	`auth`	VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT ''브라우저 인증 비밀값(base64url)'',
	`user_agent`	VARCHAR(500)	NULL	COMMENT ''브라우저 정보'',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT ''구독 최초 저장 시각'',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT ''구독 정보 마지막 갱신 시각'',
	PRIMARY KEY (`subscription_id`),
	CONSTRAINT uk_push_subscription_endpoint UNIQUE (`endpoint`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- 9. 같은 분 중복 발송 방지 테이블. 기존 행을 유지합니다.
SET @medication_sql = IF(@medication_ready AND (TRUE),
    'CREATE TABLE IF NOT EXISTS `notification_dispatch` (
	`dose_at`	DATETIME	NOT NULL	COMMENT ''발송을 맡은 복용 시각(한국 시간, 분 단위). 같은 분은 한 번만 보냄'',
	`claimed_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT ''실행이 이 시각을 가져간 시각'',
	PRIMARY KEY (`dose_at`)
) ENGINE=InnoDB',
    'DO 0');
PREPARE medication_stmt FROM @medication_sql;
EXECUTE medication_stmt;
DEALLOCATE PREPARE medication_stmt;

-- MANUAL은 기존 source VARCHAR(30)에 저장합니다. 별도 ENUM/타입 변경은 없습니다.
SELECT IF(@medication_ready, 'READY', 'BLOCKED') AS medication_update_status;
