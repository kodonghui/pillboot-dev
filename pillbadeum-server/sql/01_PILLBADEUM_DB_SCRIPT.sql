-- pilldb 최초 테이블 생성: 연결할 데이터베이스를 먼저 선택하세요.
-- 기존 테이블/데이터를 삭제하거나 변경하지 않습니다. 스키마 변경은 별도 검토합니다.

CREATE TABLE IF NOT EXISTS member
(
    member_id BIGINT AUTO_INCREMENT COMMENT '회원 코드',
    email VARCHAR(50) NOT NULL COMMENT '이메일',
    password_hash VARCHAR(255) NOT NULL COMMENT '비밀번호 BCrypt 해시',
    nickname VARCHAR(30) NOT NULL COMMENT '닉네임',
    user_role VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER' COMMENT '권한',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    CONSTRAINT pk_member PRIMARY KEY (member_id),
    CONSTRAINT uk_member_email UNIQUE (email),
    CONSTRAINT ck_member_rolee CHECK (user_role IN ('ROLE_USER', 'ROLE_ADMIN'))
) ENGINE=INNODB COMMENT '회원';

CREATE TABLE IF NOT EXISTS terms
(
    terms_id BIGINT AUTO_INCREMENT COMMENT '약관 코드',
    terms_type VARCHAR(30) NOT NULL COMMENT '약관 종류',
    terms_version VARCHAR(10) NOT NULL COMMENT '약관 버전',
    terms_title VARCHAR(100) NOT NULL COMMENT '약관 제목',
    terms_content TEXT NOT NULL COMMENT '약관 내용',
    is_required BOOLEAN NOT NULL COMMENT '필수 여부',
    is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '활성 여부',
    effective_at DATETIME NOT NULL COMMENT '시행일시',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    CONSTRAINT pk_terms PRIMARY KEY (terms_id),
    CONSTRAINT uk_terms_type_version UNIQUE (terms_type, terms_version)
) ENGINE=INNODB COMMENT '약관';

CREATE TABLE IF NOT EXISTS profile
(
    profile_id BIGINT AUTO_INCREMENT COMMENT '프로필 코드',
    member_id BIGINT NOT NULL COMMENT '회원 코드',
    profile_name VARCHAR(30) NOT NULL COMMENT '프로필명',
    relationship_type VARCHAR(20) NOT NULL COMMENT '회원과의 관계',
    profile_birth_year INT NOT NULL COMMENT '출생년도',
    profile_gender VARCHAR(10) NOT NULL COMMENT '성별',
    profile_height INT NULL COMMENT '키(cm)',
    profile_weight INT NULL COMMENT '몸무게(kg)',
    profile_pregnant BOOLEAN NOT NULL DEFAULT FALSE COMMENT '임신 여부',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    CONSTRAINT pk_profile PRIMARY KEY (profile_id),
    CONSTRAINT uk_profile_member_name UNIQUE (member_id, profile_name),
    CONSTRAINT fk_profile_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT ck_profile_gender CHECK (profile_gender IN ('MALE', 'FEMALE', 'UNKNOWN'))
) ENGINE=INNODB COMMENT '회원 프로필';

CREATE TABLE IF NOT EXISTS consent_history
(
    consent_history_id BIGINT AUTO_INCREMENT COMMENT '약관 동의 이력 코드',
    member_id BIGINT NOT NULL COMMENT '회원 코드',
    terms_id BIGINT NOT NULL COMMENT '약관 코드',
    consent_status VARCHAR(10) NOT NULL COMMENT '동의 상태',
    recorded_at DATETIME NOT NULL COMMENT '동의 또는 철회 일시',
    CONSTRAINT pk_consent_history PRIMARY KEY (consent_history_id),
    CONSTRAINT fk_consent_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_consent_terms FOREIGN KEY (terms_id) REFERENCES terms (terms_id),
    CONSTRAINT ck_consent_status CHECK (consent_status IN ('AGREED', 'WITHDRAWN'))
) ENGINE=INNODB COMMENT '약관 동의 이력';

CREATE TABLE IF NOT EXISTS notification_setting
(
    setting_id BIGINT AUTO_INCREMENT COMMENT '알림 설정 코드',
    member_id BIGINT NOT NULL COMMENT '회원 코드',
    push_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '푸시 알림 사용 여부',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 변경 시각',
    CONSTRAINT pk_notification_setting PRIMARY KEY (setting_id),
    CONSTRAINT uk_notification_member UNIQUE (member_id),
    CONSTRAINT fk_notification_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=INNODB COMMENT '알림 설정';

CREATE TABLE IF NOT EXISTS drug
(
    item_seq VARCHAR(100) COMMENT '의약품 품목기준코드',
    name VARCHAR(100) NOT NULL COMMENT '제품명',
    maker VARCHAR(100) NOT NULL COMMENT '제조사',
    etc_otc VARCHAR(10) NOT NULL COMMENT '전문/일반 구분',
    class_name VARCHAR(100) NOT NULL COMMENT '분류명',
    image_url VARCHAR(255) NULL COMMENT '약 이미지 URL',
    print_front VARCHAR(50) NULL COMMENT '앞면 식별문자',
    print_back VARCHAR(50) NULL COMMENT '뒷면 식별문자',
    shape VARCHAR(50) NOT NULL COMMENT '모양',
    color VARCHAR(50) NOT NULL COMMENT '색상',
    line_front VARCHAR(10) NULL COMMENT '앞면 분할선',
    line_back VARCHAR(10) NULL COMMENT '뒷면 분할선',
    form VARCHAR(30) NOT NULL COMMENT '제형',
    material VARCHAR(100) NOT NULL COMMENT '성분',
    synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '동기화 시각',
    CONSTRAINT pk_drug PRIMARY KEY (item_seq),
    CONSTRAINT ck_drug_etc_otc CHECK (etc_otc IN ('ETC', 'OTC'))
) ENGINE=INNODB COMMENT '의약품';

CREATE TABLE IF NOT EXISTS my_drug
(
    my_drug_id BIGINT AUTO_INCREMENT COMMENT '내 약 코드',
    profile_id BIGINT NOT NULL COMMENT '프로필 코드',
    item_seq VARCHAR(100) NOT NULL COMMENT '의약품 품목기준코드',
    dose_per_time INT NOT NULL COMMENT '1회 복용량',
    timing VARCHAR(50) NULL COMMENT '복용 시점',
    start_date DATE NOT NULL COMMENT '복용 시작일',
    end_date DATE NOT NULL COMMENT '복용 종료일',
    dose_times VARCHAR(50) NOT NULL COMMENT '하루 복용 시각 목록',
    source VARCHAR(30) NOT NULL COMMENT '등록 출처',
    registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    CONSTRAINT pk_my_drug PRIMARY KEY (my_drug_id),
    CONSTRAINT fk_my_drug_profile FOREIGN KEY (profile_id) REFERENCES profile (profile_id),
    CONSTRAINT fk_my_drug_drug FOREIGN KEY (item_seq) REFERENCES drug (item_seq),
    CONSTRAINT ck_my_drug_dose CHECK (dose_per_time > 0),
    CONSTRAINT ck_my_drug_date CHECK (end_date >= start_date),
    CONSTRAINT ck_my_drug_source CHECK (source IN ('PRESCRIPTION_PHOTO', 'PILL_PHOTO', 'SEARCH'))
) ENGINE=INNODB COMMENT '프로필별 복용 약';

CREATE TABLE IF NOT EXISTS chat_session
(
    session_id BIGINT AUTO_INCREMENT COMMENT '대화 세션 코드',
    member_id BIGINT NOT NULL COMMENT '회원 코드',
    profile_id BIGINT NOT NULL COMMENT '프로필 코드',
    title VARCHAR(255) NULL COMMENT '대화방 제목',
    last_response_id VARCHAR(255) NULL COMMENT 'OpenAI 응답 연결 ID',
    created_at DATETIME NOT NULL COMMENT '대화방 생성 시각',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 대화 시각',
    snap_birth_year INT NOT NULL COMMENT '세션 생성 시 프로필 출생년도',
    snap_gender VARCHAR(10) NOT NULL COMMENT '세션 생성 시 프로필 성별',
    snap_pregnant BOOLEAN NULL COMMENT '세션 생성 시 프로필 임신 여부',
    CONSTRAINT pk_chat_session PRIMARY KEY (session_id),
    CONSTRAINT fk_session_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_session_profile FOREIGN KEY (profile_id) REFERENCES profile (profile_id)
) ENGINE=INNODB COMMENT 'AI 상담 대화 세션';

CREATE TABLE IF NOT EXISTS chat_message
(
    message_id BIGINT AUTO_INCREMENT COMMENT '메시지 코드',
    session_id BIGINT NOT NULL COMMENT '대화 세션 코드',
    turn_no INT NOT NULL COMMENT '대화 턴 번호',
    role VARCHAR(20) NOT NULL COMMENT 'USER 또는 ASSISTANT',
    content TEXT NOT NULL COMMENT '메시지 내용',
    token_count INT NULL COMMENT '메시지 토큰 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 시각',
    CONSTRAINT pk_chat_message PRIMARY KEY (message_id),
    CONSTRAINT uk_chat_message_session_turn UNIQUE (session_id, turn_no),
    CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES chat_session (session_id),
    CONSTRAINT ck_message_role CHECK (role IN ('USER', 'ASSISTANT'))
) ENGINE=INNODB COMMENT 'AI 상담 메시지';

CREATE TABLE IF NOT EXISTS chat_summary
(
    summary_id BIGINT AUTO_INCREMENT COMMENT '대화 요약 코드',
    session_id BIGINT NOT NULL COMMENT '대화 세션 코드',
    from_turn INT NOT NULL COMMENT '요약 시작 턴',
    to_turn INT NOT NULL COMMENT '요약 종료 턴',
    content TEXT NOT NULL COMMENT '대화 요약 내용',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '요약 생성 시각',
    CONSTRAINT pk_chat_summary PRIMARY KEY (summary_id),
    CONSTRAINT uk_chat_summary_range UNIQUE (session_id, from_turn, to_turn),
    CONSTRAINT fk_summary_session FOREIGN KEY (session_id) REFERENCES chat_session (session_id),
    CONSTRAINT ck_summary_turn_range CHECK (to_turn >= from_turn)
) ENGINE=INNODB COMMENT 'AI 상담 대화 요약';
