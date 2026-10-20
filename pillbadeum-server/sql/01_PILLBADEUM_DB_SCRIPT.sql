-- ERD Cloud 내보내기 SQL 기준: 관계형 14개 테이블, 114개 컬럼.
-- vector_point는 Qdrant 모델이므로 MySQL 생성 대상에서 제외합니다.
-- 원문의 PK/FK를 생성 시점에 유효하도록 CREATE TABLE 내부에 배치했습니다.
-- consent.terms_title의 VARHCHAR(30)는 실행 불가능한 자료형 오타여서 VARCHAR(30)로 수정했습니다.
-- 사용자 결정에 따라 chat_session.snap_pregnant는 BOOLEAN으로 통일하고 기존 NULL 허용은 유지합니다.
-- 최신 내보내기의 소문자 표명과 photo.kind VARCHAR(30)을 반영했습니다.
-- 사용자 결정으로 drug.etc_otc UNIQUE를 제거하고 ETC/OTC만 허용하는 CHECK를 적용합니다.
-- 일정 한 건만 삭제하고 약을 유지하기 위해 my_drug 일정 3개 필드를 NULL 허용으로 변경합니다.
-- 일정은 세 값이 모두 NULL(없음)이거나 모두 NOT NULL(있음)이어야 합니다. 기본 시각은 앱에서 채웁니다.
-- 사용자 결정으로 복약 알림용 push_subscription·notification_dispatch 2개 표를 추가해 16개 표·124개 컬럼입니다.
-- notification_setting은 회원당 1행이 되도록 member_id에 UNIQUE를 적용합니다.
-- 팀 결정으로 컬럼명 오타 profile.realationship_type → relationship_type, drug.matrial → material로 바로잡았습니다.
-- 위 교정·결정 외 컬럼명/자료형/NULL/기본값/제약은 내보내기 원문을 유지합니다.
-- 00번으로 DB를 준비한 뒤 대상 DB를 선택하여 실행하세요.
-- 기존 테이블을 삭제하거나 변경하지 않습니다. 기존 DB의 변경은 별도 검토가 필요합니다.

CREATE TABLE IF NOT EXISTS `member` (
	`member_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '회원 코드',
	`email`	VARCHAR(100)UNIQUE	NOT NULL	COMMENT '이메일(UK)',
	`password_hash`	VARCHAR(255)	NOT NULL	COMMENT '비밀번호',
	`nickname`	VARCHAR(30)	NOT NULL	COMMENT '닉네임',
	`user_role`	VARCHAR(20)	NOT NULL	COMMENT '권한',
	`created_at`	DATETIME	NOT NULL	COMMENT '가입일시',
	`updated_at`	DATETIME	NOT NULL	COMMENT '수정일시',
	PRIMARY KEY (`member_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `consent` (
	`terms_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '약관코드',
	`terms_type`	VARCHAR(20)	NOT NULL	COMMENT '약관종류',
	`terms_version`	VARCHAR(10)	NOT NULL	COMMENT '약관버전',
	`terms_title`	VARCHAR(30)	NOT NULL	COMMENT '약관제목',
	`terms_content`	TEXT	NOT NULL	COMMENT '약관내용',
	`is_required`	BOOLEAN	NOT NULL	COMMENT '필수여부',
	`is_active`	BOOLEAN	NOT NULL	COMMENT '활성여부',
	`effective_at`	DATETIME	NOT NULL	COMMENT '시행일시',
	`created_at`	DATETIME	NOT NULL	COMMENT '등록일시',
	PRIMARY KEY (`terms_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `drug` (
	`item_seq`	VARCHAR(100)	NOT NULL,
	`name`	VARCHAR(50)	NOT NULL	COMMENT 'ITEM_NAME',
	`maker`	VARCHAR(50)	NOT NULL	COMMENT 'ENTP_NAME',
	`etc_otc`	VARCHAR(10)	NOT NULL,
	`class_name`	VARCHAR(50)	NOT NULL,
	`image_url`	VARCHAR(255)	NULL	COMMENT '이미지가 등록되지 않은약물 있음(nullable)',
	`print_front`	VARCHAR(50)	NULL	COMMENT '앞 또는 뒤 식별기호없을 경우 있음',
	`print_back`	VARCHAR(50)	NULL	COMMENT '//',
	`shape`	VARCHAR(50)	NOT NULL,
	`color`	VARCHAR(50)	NOT NULL,
	`line_front`	VARCHAR(10)	NULL	COMMENT '분할선 없는 경우도 있음',
	`line_back`	VARCHAR(10)	NULL	COMMENT '//',
	`form`	VARCHAR(10)	NOT NULL	COMMENT '정제, 연질캡슐, 경질캡슐(통상적인 캡슐모양)',
	`material`	VARCHAR(30)	NOT NULL,
	`synced_at`	DATETIME	NOT NULL,
	PRIMARY KEY (`item_seq`),
	CONSTRAINT chk_drug_type CHECK (`etc_otc` IN ('ETC', 'OTC'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `profile` (
	`profile_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '프로필코드',
	`member_id`	BIGINT	NOT NULL	COMMENT '회원 코드',
	`profile_name`	VARCHAR(10)	NOT NULL	COMMENT '프로필명',
	`relationship_type`	VARCHAR(10)	NOT NULL	COMMENT '회원과의 관계',
	`profile_birth_year`	INT	NOT NULL	COMMENT '출생년도',
	`profile_gender`	VARCHAR(10)	NOT NULL	COMMENT '성별',
	`profile_height`	INT	NULL	COMMENT '키',
	`profile_weight`	INT	NULL	COMMENT '몸무게',
	`profile_pregnant`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '임신여부',
	`created_at`	DATETIME	NOT NULL	COMMENT '생성일시',
	`updated_at`	DATETIME	NOT NULL	COMMENT '수정일시',
	PRIMARY KEY (`profile_id`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `consent_history` (
	`consent_history_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '약관동의코드',
	`member_id`	BIGINT	NOT NULL	COMMENT '회원 코드',
	`terms_id`	BIGINT	NOT NULL	COMMENT '약관코드',
	`consent_status`	VARCHAR(10)	NOT NULL	COMMENT '약관동의상태',
	`recorded_at`	DATETIME	NOT NULL	COMMENT '동의일시',
	PRIMARY KEY (`consent_history_id`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
	FOREIGN KEY (`terms_id`) REFERENCES `consent` (`terms_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `notification_setting` (
	`setting_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '알림 설정 고유 식별자',
	`member_id`	BIGINT	NOT NULL	COMMENT '회원 코드',
	`push_enabled`	BOOLEAN	NOT NULL	DEFAULT FALSE	COMMENT '알림 동의 여부와 연동',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '알림 설정 마지막 변경 시각',
	PRIMARY KEY (`setting_id`),
	CONSTRAINT uk_notification_setting_member UNIQUE (`member_id`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `push_subscription` (
	`subscription_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '브라우저 푸시 구독 코드',
	`member_id`	BIGINT	NOT NULL	COMMENT '회원 코드, 이 브라우저로 마지막에 알림을 켠 회원',
	`endpoint`	VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT '브라우저 푸시 주소(UK), 대소문자 구분',
	`p256dh`	VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT '브라우저 공개 키(base64url)',
	`auth`	VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT '브라우저 인증 비밀값(base64url)',
	`user_agent`	VARCHAR(500)	NULL	COMMENT '브라우저 정보',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '구독 최초 저장 시각',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '구독 정보 마지막 갱신 시각',
	PRIMARY KEY (`subscription_id`),
	CONSTRAINT uk_push_subscription_endpoint UNIQUE (`endpoint`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `notification_dispatch` (
	`dose_at`	DATETIME	NOT NULL	COMMENT '발송을 맡은 복용 시각(한국 시간, 분 단위). 같은 분은 한 번만 보냄',
	`claimed_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '실행이 이 시각을 가져간 시각',
	PRIMARY KEY (`dose_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `my_drug` (
	`my_drug_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '등록한 내약 고유 식별자',
	`profile_id`	BIGINT	NOT NULL	COMMENT '프로필코드',
	`item_seq`	VARCHAR(100)	NULL DEFAULT NULL,
	`custom_name`	VARCHAR(100)	NULL DEFAULT NULL	COMMENT '약품을 특정하지 않은 직접 입력 이름',
	`dose_per_time`	INT	NOT NULL	COMMENT '한 번에 복용하는 약 개수',
	`timing`	VARCHAR(50)	NULL	COMMENT '식후 30분, 식전 등',
	`start_date`	DATE	NULL DEFAULT NULL	COMMENT '복용 시작 날짜, 일정 없으면 NULL',
	`end_date`	DATE	NULL DEFAULT NULL	COMMENT '복용 종료 날짜, 일정 등록 시 필수',
	`dose_times`	VARCHAR(50)	NULL DEFAULT NULL	COMMENT '하루 1~3개 복용 시각, 일정 없으면 NULL',
	`source`	VARCHAR(30)	NOT NULL	COMMENT 'PRESCRIPTION_PHOTO / PILL_PHOTO / SEARCH / MANUAL',
	`registered_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '내약 최초 등록 시각',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '내약 정보 마지막 수정 시각',
	PRIMARY KEY (`my_drug_id`),
	FOREIGN KEY (`profile_id`) REFERENCES `profile` (`profile_id`),
	FOREIGN KEY (`item_seq`) REFERENCES `drug` (`item_seq`),
	CONSTRAINT chk_my_drug_identity CHECK (
		`item_seq` IS NOT NULL OR (`custom_name` IS NOT NULL AND CHAR_LENGTH(TRIM(`custom_name`)) > 0)
	),
	CONSTRAINT chk_my_drug_schedule_presence CHECK (
		(`start_date` IS NULL AND `end_date` IS NULL AND `dose_times` IS NULL)
		OR (`start_date` IS NOT NULL AND `end_date` IS NOT NULL AND `dose_times` IS NOT NULL)
	)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `chat_session` (
	`session_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '대화방 고유 식별자',
	`member_id`	BIGINT	NOT NULL	COMMENT '회원 코드',
	`profile_id`	BIGINT	NULL	COMMENT '프로필코드',
	`title`	VARCHAR(255)	NULL	COMMENT '대화방 제목',
	`last_response_id`	VARCHAR(255)	NULL	COMMENT 'OpenAI 응답 연결에 사용하는 보조 ID',
	`created_at`	DATETIME	NOT NULL	COMMENT '대화방 생성 시각',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '마지막 대화 시각, 100일 뒤 삭제 기준',
	`snap_birth_year`	BIGINT	NOT NULL	COMMENT '세션 시작 첫 질문에 들어갈 정보',
	`snap_gender`	VARCHAR(10)	NOT NULL	COMMENT '//',
	`snap_pregnant`	BOOLEAN	NULL	COMMENT '//',
	PRIMARY KEY (`session_id`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
	FOREIGN KEY (`profile_id`) REFERENCES `profile` (`profile_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `chat_message` (
	`message_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '메시지 고유 식별자',
	`session_id`	BIGINT	NOT NULL	COMMENT '대화방 고유 식별자',
	`trun_no`	INT	NOT NULL	COMMENT '몇번째 대화 턴인지 구분',
	`role`	VARCHAR(20)	NOT NULL	COMMENT 'user/assistant',
	`content`	TEXT	NOT NULL	COMMENT '검사를 통과한 메세지만 저장',
	`token_count`	INT	NULL	COMMENT '메시지 토큰수, 미계산 시 NULL',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '메시지 생성 시각',
	PRIMARY KEY (`message_id`),
	FOREIGN KEY (`session_id`) REFERENCES `chat_session` (`session_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `chat_summary` (
	`summary_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '요약 고유 식별자',
	`session_id`	BIGINT	NOT NULL	COMMENT '대화방 고유 식별자',
	`from_turn`	INT	NOT NULL	COMMENT '요약 대상의 첫 번쨰 번호',
	`to_turn`	INT	NOT NULL	COMMENT '요약 대상의 마지막 턴 번호',
	`content`	TEXT	NOT NULL	COMMENT '10턴 단위 대화 압축 요약',
	`created_at`	DATETIME	NOT NULL	COMMENT '요약 생성 시각',
	PRIMARY KEY (`summary_id`),
	FOREIGN KEY (`session_id`) REFERENCES `chat_session` (`session_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `photo` (
	`photo_id`	BIGINT AUTO_INCREMENT	NOT NULL,
	`member_id`	BIGINT	NOT NULL	COMMENT '회원 코드',
	`profile_id`	BIGINT	NULL	COMMENT '프로필코드',
	`kind`	VARCHAR(30)	NOT NULL	COMMENT '처방전, 약봉투, 알약',
	`side`	VARCHAR(10)	NULL	COMMENT 'FRONT, BACK, 처방전은 NULL',
	`temp_path`	VARCHAR(255)	NOT NULL	COMMENT 'UUID 파일 이름, 원본만',
	`mime_type`	VARCHAR(30)	NOT NULL,
	`size_bytes`	BIGINT	NOT NULL,
	`status`	VARCHAR(10)	NOT NULL	COMMENT 'UPLOADED 식별중 / IDENTIFIED 후보 확인 대기',
	`uploaded_at`	DATETIME	NOT NULL	COMMENT '30분 지나면 정리 작업(10분 마다)이 삭제',
	PRIMARY KEY (`photo_id`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
	FOREIGN KEY (`profile_id`) REFERENCES `profile` (`profile_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `ocr_candidate` (
	`ocr_candidate_id`	BIGINT AUTO_INCREMENT	NOT NULL,
	`photo_id`	BIGINT	NOT NULL,
	`read_no`	INT	NOT NULL	COMMENT '처방전에서 읽은 약 1-10/ 알약1',
	`read_name`	VARCHAR(50)	NULL	COMMENT '읽은 약 이름 글자만 저장',
	`candidate_rank`	INT	NOT NULL,
	`lookup_status`	VARCHAR(10)	NULL	COMMENT 'OK/NOT_FOUND/FAILED',
	`print_matched`	BOOLEAN	NULL,
	PRIMARY KEY (`ocr_candidate_id`),
	FOREIGN KEY (`photo_id`) REFERENCES `photo` (`photo_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `dur_warning` (
	`dur_warning_id`	BIGINT AUTO_INCREMENT	NOT NULL,
	`item_seq`	VARCHAR(100)	NOT NULL,
	`type`	VARCHAR(50)	NULL	COMMENT '임부, 연령, 노인, 용량',
	`ingredient`	VARCHAR(50)	NULL,
	`effect`	VARCHAR(100)	NULL	COMMENT '금기내용',
	`content`	TEXT	NOT NULL	COMMENT 'API에서 받은 상세 금기 주의내용',
	`synced_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '공공데이터를 가져온 시각',
	PRIMARY KEY (`dur_warning_id`),
	FOREIGN KEY (`item_seq`) REFERENCES `drug` (`item_seq`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `dur_interaction` (
	`dur_interaction_id`	BIGINT AUTO_INCREMENT	NOT NULL,
	`item_seq2`	VARCHAR(100)	NOT NULL,
	`mixture_item_seq`	VARCHAR(50)	NULL	COMMENT '병용금기 없는 약물의 경우 null',
	`content`	VARCHAR(255)	NULL,
	`synced_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '공공데이터를 가져온 시각',
	PRIMARY KEY (`dur_interaction_id`),
	FOREIGN KEY (`item_seq2`) REFERENCES `drug` (`item_seq`)
) ENGINE=InnoDB;
