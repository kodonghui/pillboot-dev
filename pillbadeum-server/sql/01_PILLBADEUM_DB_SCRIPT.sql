-- 최신 신규 설치용 MySQL 구조:18개 테이블·146개 컬럼임. 대상 DB를 선택한 뒤 전체 실행함.
-- 현재 적용된03~12의 구조를 통합함. drug.material은 처음부터TEXT NOT NULL임. 가입 기본 약관은13에서 준비함.
-- 새 DB는00 → 01, 샘플이 필요할 때만02를 실행함.03~11을 다시 적용할 필요가 없음.
-- 기존 DB는01로 업그레이드하지 않으며 필요한03~11 패치만 실행함.
-- CREATE IF NOT EXISTS는 기존 값/테이블을 보존할 뿐 기존 컬럼 구조를 갱신하지 않음.
-- DROP·업무 데이터 입력/수정·FOREIGN_KEY_CHECKS 해제를 포함하지 않음.
-- 원본14개 테이블 구조와 변경 이력은Git과03~11에 보존함. Qdrant는 별도임.

-- member 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `member` (
  `member_id` bigint NOT NULL AUTO_INCREMENT COMMENT '회원 코드',
  `email` varchar(100) NOT NULL COMMENT '이메일(UK)',
  `password_hash` varchar(255) NOT NULL COMMENT '비밀번호',
  `nickname` varchar(30) NOT NULL COMMENT '닉네임',
  `user_role` varchar(20) NOT NULL COMMENT '권한',
  `created_at` datetime NOT NULL COMMENT '가입일시',
  `updated_at` datetime NOT NULL COMMENT '수정일시',
  PRIMARY KEY (`member_id`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- consent 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `consent` (
  `terms_id` bigint NOT NULL AUTO_INCREMENT COMMENT '약관코드',
  `terms_type` varchar(20) NOT NULL COMMENT '약관종류',
  `terms_version` varchar(10) NOT NULL COMMENT '약관버전',
  `terms_title` varchar(30) NOT NULL COMMENT '약관제목',
  `terms_content` text NOT NULL COMMENT '약관내용',
  `is_required` tinyint(1) NOT NULL COMMENT '필수여부',
  `is_active` tinyint(1) NOT NULL COMMENT '활성여부',
  `effective_at` datetime NOT NULL COMMENT '시행일시',
  `created_at` datetime NOT NULL COMMENT '등록일시',
  PRIMARY KEY (`terms_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- drug 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `drug` (
  `item_seq` varchar(100) NOT NULL,
  `name` varchar(50) NOT NULL COMMENT 'ITEM_NAME',
  `maker` varchar(50) NOT NULL COMMENT 'ENTP_NAME',
  `etc_otc` varchar(10) NOT NULL,
  `class_name` varchar(50) NOT NULL,
  `image_url` varchar(255) DEFAULT NULL COMMENT '이미지가 등록되지 않은약물 있음(nullable)',
  `print_front` varchar(50) DEFAULT NULL COMMENT '앞 또는 뒤 식별기호없을 경우 있음',
  `print_back` varchar(50) DEFAULT NULL COMMENT '//',
  `shape` varchar(50) NOT NULL,
  `color` varchar(50) NOT NULL,
  `line_front` varchar(10) DEFAULT NULL COMMENT '분할선 없는 경우도 있음',
  `line_back` varchar(10) DEFAULT NULL COMMENT '//',
  `form` varchar(10) NOT NULL COMMENT '정제, 연질캡슐, 경질캡슐(통상적인 캡슐모양)',
  `material` text NOT NULL COMMENT '공식 성분명 전체',
  `synced_at` datetime NOT NULL,
  `metadata_source` varchar(20) NOT NULL DEFAULT 'UNKNOWN',
  `cancelled` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`item_seq`),
  CONSTRAINT `chk_drug_type` CHECK ((`etc_otc` in (_utf8mb4'ETC',_utf8mb4'OTC')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- profile 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `profile` (
  `profile_id` bigint NOT NULL AUTO_INCREMENT COMMENT '프로필코드',
  `member_id` bigint NOT NULL COMMENT '회원 코드',
  `profile_name` varchar(10) NOT NULL COMMENT '프로필명',
  `relationship_type` varchar(10) NOT NULL COMMENT '회원과의 관계',
  `profile_birth_year` int NOT NULL COMMENT '출생년도',
  `profile_gender` varchar(10) NOT NULL COMMENT '성별',
  `profile_height` int DEFAULT NULL COMMENT '키',
  `profile_weight` int DEFAULT NULL COMMENT '몸무게',
  `profile_pregnant` tinyint(1) NOT NULL DEFAULT '0' COMMENT '임신여부',
  `created_at` datetime NOT NULL COMMENT '생성일시',
  `updated_at` datetime NOT NULL COMMENT '수정일시',
  PRIMARY KEY (`profile_id`),
  KEY `member_id` (`member_id`),
  CONSTRAINT `profile_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- consent_history 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `consent_history` (
  `consent_history_id` bigint NOT NULL AUTO_INCREMENT COMMENT '약관동의코드',
  `member_id` bigint NOT NULL COMMENT '회원 코드',
  `terms_id` bigint NOT NULL COMMENT '약관코드',
  `consent_status` varchar(10) NOT NULL COMMENT '약관동의상태',
  `recorded_at` datetime NOT NULL COMMENT '동의일시',
  PRIMARY KEY (`consent_history_id`),
  KEY `member_id` (`member_id`),
  KEY `terms_id` (`terms_id`),
  CONSTRAINT `consent_history_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
  CONSTRAINT `consent_history_ibfk_2` FOREIGN KEY (`terms_id`) REFERENCES `consent` (`terms_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- notification_setting 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `notification_setting` (
  `setting_id` bigint NOT NULL AUTO_INCREMENT COMMENT '알림 설정 고유 식별자',
  `member_id` bigint NOT NULL COMMENT '회원 코드',
  `push_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '알림 동의 여부와 연동',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '알림 설정 마지막 변경 시각',
  PRIMARY KEY (`setting_id`),
  UNIQUE KEY `uk_notification_setting_member` (`member_id`),
  CONSTRAINT `notification_setting_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- my_drug 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `my_drug` (
  `my_drug_id` bigint NOT NULL AUTO_INCREMENT COMMENT '등록한 내약 고유 식별자',
  `profile_id` bigint NOT NULL COMMENT '프로필코드',
  `item_seq` varchar(100) DEFAULT NULL,
  `custom_name` varchar(100) DEFAULT NULL COMMENT '약품을 특정하지 않은 직접 입력 이름',
  `dose_per_time` int NOT NULL COMMENT '한 번에 복용하는 약 개수',
  `timing` varchar(50) DEFAULT NULL COMMENT '식후 30분, 식전 등',
  `start_date` date DEFAULT NULL COMMENT '복용 시작 날짜, 일정 없으면 NULL',
  `end_date` date DEFAULT NULL COMMENT '복용 종료 날짜, 일정 등록 시 필수',
  `dose_times` varchar(50) DEFAULT NULL COMMENT '하루 1~3개 복용 시각, 일정 없으면 NULL',
  `source` varchar(30) NOT NULL COMMENT 'PRESCRIPTION_PHOTO / PILL_PHOTO / SEARCH',
  `registered_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '내약 최초 등록 시각',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '내약 정보 마지막 수정 시각',
  `dose_confirmed` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`my_drug_id`),
  UNIQUE KEY `uk_my_drug_profile_item` (`profile_id`,`item_seq`),
  KEY `item_seq` (`item_seq`),
  CONSTRAINT `my_drug_ibfk_1` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`profile_id`),
  CONSTRAINT `my_drug_ibfk_2` FOREIGN KEY (`item_seq`) REFERENCES `drug` (`item_seq`),
  CONSTRAINT `chk_my_drug_identity` CHECK (((`item_seq` is not null) or ((`custom_name` is not null) and (char_length(trim(`custom_name`)) > 0)))),
  CONSTRAINT `chk_my_drug_schedule_presence` CHECK ((((`start_date` is null) and (`end_date` is null) and (`dose_times` is null)) or ((`start_date` is not null) and (`end_date` is not null) and (`dose_times` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- chat_session 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `chat_session` (
  `session_id` bigint NOT NULL AUTO_INCREMENT COMMENT '대화방 고유 식별자',
  `member_id` bigint NOT NULL COMMENT '회원 코드',
  `profile_id` bigint DEFAULT NULL COMMENT '프로필코드',
  `title` varchar(255) DEFAULT NULL COMMENT '대화방 제목',
  `last_response_id` varchar(255) DEFAULT NULL COMMENT 'OpenAI 응답 연결에 사용하는 보조 ID',
  `created_at` datetime NOT NULL COMMENT '대화방 생성 시각',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '마지막 대화 시각, 100일 뒤 삭제 기준',
  `snap_birth_year` bigint NOT NULL COMMENT '세션 시작 첫 질문에 들어갈 정보',
  `snap_gender` varchar(10) NOT NULL COMMENT '//',
  `snap_pregnant` tinyint(1) DEFAULT NULL COMMENT '//',
  PRIMARY KEY (`session_id`),
  KEY `member_id` (`member_id`),
  KEY `profile_id` (`profile_id`),
  CONSTRAINT `chat_session_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
  CONSTRAINT `chat_session_ibfk_2` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`profile_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- chat_message 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `chat_message` (
  `message_id` bigint NOT NULL AUTO_INCREMENT COMMENT '메시지 고유 식별자',
  `session_id` bigint NOT NULL COMMENT '대화방 고유 식별자',
  `trun_no` int NOT NULL COMMENT '몇번째 대화 턴인지 구분',
  `role` varchar(20) NOT NULL COMMENT 'user/assistant',
  `content` text NOT NULL COMMENT '검사를 통과한 메세지만 저장',
  `token_count` int DEFAULT NULL COMMENT '메시지 토큰수, 미계산 시 NULL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '메시지 생성 시각',
  `dur_notice` json DEFAULT NULL,
  PRIMARY KEY (`message_id`),
  KEY `session_id` (`session_id`),
  CONSTRAINT `chat_message_ibfk_1` FOREIGN KEY (`session_id`) REFERENCES `chat_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- chat_summary 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `chat_summary` (
  `summary_id` bigint NOT NULL AUTO_INCREMENT COMMENT '요약 고유 식별자',
  `session_id` bigint NOT NULL COMMENT '대화방 고유 식별자',
  `from_turn` int NOT NULL COMMENT '요약 대상의 첫 번쨰 번호',
  `to_turn` int NOT NULL COMMENT '요약 대상의 마지막 턴 번호',
  `content` text NOT NULL COMMENT '10턴 단위 대화 압축 요약',
  `created_at` datetime NOT NULL COMMENT '요약 생성 시각',
  PRIMARY KEY (`summary_id`),
  KEY `session_id` (`session_id`),
  CONSTRAINT `chat_summary_ibfk_1` FOREIGN KEY (`session_id`) REFERENCES `chat_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- photo 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `photo` (
  `photo_id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT '회원 코드',
  `profile_id` bigint DEFAULT NULL COMMENT '프로필코드',
  `kind` varchar(30) NOT NULL COMMENT '처방전, 약봉투, 알약',
  `side` varchar(10) DEFAULT NULL COMMENT 'FRONT, BACK, 처방전은 NULL',
  `front_photo_id` bigint DEFAULT NULL,
  `temp_path` varchar(255) NOT NULL COMMENT 'UUID 파일 이름, 원본만',
  `mime_type` varchar(30) NOT NULL,
  `size_bytes` bigint NOT NULL,
  `status` varchar(10) NOT NULL COMMENT 'UPLOADED 식별중 / IDENTIFIED 후보 확인 대기',
  `uploaded_at` datetime NOT NULL COMMENT '30분 지나면 정리 작업(10분 마다)이 삭제',
  PRIMARY KEY (`photo_id`),
  KEY `member_id` (`member_id`),
  KEY `profile_id` (`profile_id`),
  KEY `fk_photo_front` (`front_photo_id`),
  CONSTRAINT `fk_photo_front` FOREIGN KEY (`front_photo_id`) REFERENCES `photo` (`photo_id`) ON DELETE CASCADE,
  CONSTRAINT `photo_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`),
  CONSTRAINT `photo_ibfk_2` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`profile_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ocr_candidate 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `ocr_candidate` (
  `ocr_candidate_id` bigint NOT NULL AUTO_INCREMENT,
  `photo_id` bigint NOT NULL,
  `read_no` int NOT NULL COMMENT '처방전에서 읽은 약 1-10/ 알약1',
  `read_name` varchar(50) DEFAULT NULL COMMENT '읽은 약 이름 글자만 저장',
  `item_seq` varchar(100) DEFAULT NULL,
  `candidate_rank` int NOT NULL,
  `lookup_status` varchar(10) DEFAULT NULL COMMENT 'OK/NOT_FOUND/FAILED',
  `print_matched` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`ocr_candidate_id`),
  KEY `photo_id` (`photo_id`),
  KEY `fk_ocr_candidate_drug` (`item_seq`),
  CONSTRAINT `fk_ocr_candidate_drug` FOREIGN KEY (`item_seq`) REFERENCES `drug` (`item_seq`),
  CONSTRAINT `ocr_candidate_ibfk_1` FOREIGN KEY (`photo_id`) REFERENCES `photo` (`photo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- dur_warning 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `dur_warning` (
  `dur_warning_id` bigint NOT NULL AUTO_INCREMENT,
  `item_seq` varchar(100) NOT NULL,
  `type` varchar(50) DEFAULT NULL COMMENT '임부, 연령, 노인, 용량',
  `ingredient` varchar(50) DEFAULT NULL,
  `effect` varchar(100) DEFAULT NULL COMMENT '금기내용',
  `content` text NOT NULL COMMENT 'API에서 받은 상세 금기 주의내용',
  `synced_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '공공데이터를 가져온 시각',
  PRIMARY KEY (`dur_warning_id`),
  KEY `item_seq` (`item_seq`),
  CONSTRAINT `dur_warning_ibfk_1` FOREIGN KEY (`item_seq`) REFERENCES `drug` (`item_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- dur_interaction 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `dur_interaction` (
  `dur_interaction_id` bigint NOT NULL AUTO_INCREMENT,
  `item_seq2` varchar(100) NOT NULL,
  `mixture_item_seq` varchar(50) DEFAULT NULL COMMENT '병용금기 없는 약물의 경우 null',
  `content` varchar(255) DEFAULT NULL,
  `synced_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '공공데이터를 가져온 시각',
  PRIMARY KEY (`dur_interaction_id`),
  KEY `item_seq2` (`item_seq2`),
  CONSTRAINT `dur_interaction_ibfk_1` FOREIGN KEY (`item_seq2`) REFERENCES `drug` (`item_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- notification_dispatch 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `notification_dispatch` (
  `dose_at` datetime NOT NULL COMMENT '발송을 맡은 복용 시각(한국 시간, 분 단위). 같은 분은 한 번만 보냄',
  `claimed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '실행이 이 시각을 가져간 시각',
  PRIMARY KEY (`dose_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- pill_identification_session 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `pill_identification_session` (
  `identification_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `member_id` bigint NOT NULL,
  `profile_id` bigint NOT NULL,
  `photo_id` bigint DEFAULT NULL,
  `origin` varchar(12) NOT NULL,
  `lifecycle` varchar(12) NOT NULL,
  `policy_version` smallint unsigned NOT NULL,
  `snapshot` json DEFAULT NULL,
  `selected_item_seq` varchar(100) DEFAULT NULL,
  `registration_receipt` json DEFAULT NULL,
  `replacement_id` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  PRIMARY KEY (`identification_id`),
  UNIQUE KEY `uq_pill_identification_photo` (`photo_id`),
  KEY `ix_pill_identification_expiry` (`expires_at`),
  KEY `ix_pill_identification_owner` (`member_id`,`profile_id`,`created_at`),
  KEY `fk_pill_identification_profile` (`profile_id`),
  CONSTRAINT `fk_pill_identification_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_pill_identification_photo` FOREIGN KEY (`photo_id`) REFERENCES `photo` (`photo_id`) ON DELETE SET NULL,
  CONSTRAINT `fk_pill_identification_profile` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`profile_id`) ON DELETE CASCADE,
  CONSTRAINT `ck_pill_identification_expiry` CHECK ((`expires_at` > `created_at`)),
  CONSTRAINT `ck_pill_identification_lifecycle` CHECK ((`lifecycle` in (_utf8mb4'ACTIVE',_utf8mb4'REPLACED',_utf8mb4'CANCELLED',_utf8mb4'CONSUMED'))),
  CONSTRAINT `ck_pill_identification_origin` CHECK ((`origin` in (_utf8mb4'PHOTO',_utf8mb4'CLUES')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- push_subscription 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `push_subscription` (
  `subscription_id` bigint NOT NULL AUTO_INCREMENT COMMENT '브라우저 푸시 구독 코드',
  `member_id` bigint NOT NULL COMMENT '회원 코드, 이 브라우저로 마지막에 알림을 켠 회원',
  `endpoint` varchar(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '브라우저 푸시 주소(UK), 대소문자 구분',
  `p256dh` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '브라우저 공개 키(base64url)',
  `auth` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '브라우저 인증 비밀값(base64url)',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '브라우저 정보',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '구독 최초 저장 시각',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '구독 정보 마지막 갱신 시각',
  PRIMARY KEY (`subscription_id`),
  UNIQUE KEY `uk_push_subscription_endpoint` (`endpoint`),
  KEY `member_id` (`member_id`),
  CONSTRAINT `push_subscription_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- vector_cleanup 테이블을 생성함. 기존 테이블은 그대로 유지함.
CREATE TABLE IF NOT EXISTS `vector_cleanup` (
  `session_id` bigint NOT NULL,
  `requested_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
