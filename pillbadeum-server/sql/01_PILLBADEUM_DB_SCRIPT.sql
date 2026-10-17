-- pilldb 최초 테이블 생성: 연결할 데이터베이스를 먼저 선택하세요.
-- 기존 테이블/데이터를 삭제하거나 변경하지 않습니다. 기존 10개 테이블 DB는 README의 03 업그레이드 절차를 따르세요.
-- 첨부 ERD의 관계형 14개 + API 명세의 웹 푸시 구독 1개 = MySQL 15개. vector_point는 Qdrant에 저장합니다.

CREATE TABLE IF NOT EXISTS member
(
    member_id BIGINT AUTO_INCREMENT COMMENT '회원 코드',
    email VARCHAR(100) NOT NULL COMMENT '이메일',
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
    profile_pregnant BOOLEAN NULL DEFAULT NULL COMMENT '임신 여부: TRUE 예 / FALSE 아니오 / NULL 미입력',
    color CHAR(7) NULL COMMENT '서버가 배정한 프로필 색 #RRGGBB; 기존 데이터는 순차 배정',
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
    maker VARCHAR(100) NULL COMMENT '제조사',
    etc_otc VARCHAR(10) NULL COMMENT '전문/일반 구분',
    class_name VARCHAR(100) NULL COMMENT '분류명',
    image_url VARCHAR(255) NULL COMMENT '약 이미지 URL',
    print_front VARCHAR(50) NULL COMMENT '앞면 식별문자',
    print_back VARCHAR(50) NULL COMMENT '뒷면 식별문자',
    shape VARCHAR(50) NULL COMMENT '모양',
    color VARCHAR(50) NULL COMMENT '앞면 색상(API colorFront)',
    color_back VARCHAR(50) NULL COMMENT '뒷면 색상(API colorBack)',
    line_front VARCHAR(10) NULL COMMENT '앞면 분할선',
    line_back VARCHAR(10) NULL COMMENT '뒷면 분할선',
    form VARCHAR(30) NULL COMMENT '제형',
    appearance TEXT NULL COMMENT '공식 성상',
    len_long DECIMAL(10,3) NULL COMMENT '장축 mm',
    len_short DECIMAL(10,3) NULL COMMENT '단축 mm',
    main_ingredient TEXT NULL COMMENT '허가정보 유효성분(API mainIngredient)',
    material TEXT NULL COMMENT '허가정보 원료성분; 원문이 길 수 있음',
    cancel_status VARCHAR(30) NULL COMMENT '공공데이터 허가 취하 상태; API cancelled로 매핑',
    dur_synced_at DATETIME NULL COMMENT 'DUR 조회 성공 시각; 0건도 성공, 실패 시 NULL 유지',
    synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '동기화 시각',
    CONSTRAINT pk_drug PRIMARY KEY (item_seq),
    CONSTRAINT ck_drug_etc_otc CHECK (etc_otc IN ('ETC', 'OTC'))
) ENGINE=INNODB COMMENT '의약품';

CREATE TABLE IF NOT EXISTS my_drug
(
    my_drug_id BIGINT AUTO_INCREMENT COMMENT '내 약 코드',
    profile_id BIGINT NOT NULL COMMENT '프로필 코드',
    item_seq VARCHAR(100) NOT NULL COMMENT '의약품 품목기준코드',
    dose_per_time INT NULL COMMENT '1회 복용량',
    timing VARCHAR(50) NULL COMMENT '복용 시점',
    start_date DATE NULL COMMENT '복용 시작일',
    end_date DATE NULL COMMENT '복용 종료일',
    dose_times VARCHAR(50) NULL COMMENT '하루 복용 시각 목록',
    source VARCHAR(30) NOT NULL COMMENT '등록 출처',
    registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    CONSTRAINT pk_my_drug PRIMARY KEY (my_drug_id),
    CONSTRAINT uk_my_drug_profile_item UNIQUE (profile_id, item_seq),
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
    snap_birth_year INT NULL COMMENT '첫 질문 시 프로필 출생년도 사본',
    snap_gender VARCHAR(10) NULL COMMENT '첫 질문 시 프로필 성별 사본',
    snap_pregnant BOOLEAN NULL COMMENT '첫 질문 시 프로필 임신 여부 사본',
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
    CONSTRAINT uk_chat_message_session_turn_role UNIQUE (session_id, turn_no, role),
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
    CONSTRAINT uk_chat_summary_start UNIQUE (session_id, from_turn),
    CONSTRAINT fk_summary_session FOREIGN KEY (session_id) REFERENCES chat_session (session_id),
    CONSTRAINT ck_summary_turn_range CHECK (to_turn >= from_turn)
) ENGINE=INNODB COMMENT 'AI 상담 대화 요약';


CREATE TABLE IF NOT EXISTS photo
(
    photo_id BIGINT AUTO_INCREMENT COMMENT '사진 코드',
    member_id BIGINT NOT NULL COMMENT '업로드한 회원',
    profile_id BIGINT NOT NULL COMMENT '사진을 등록한 대상 프로필',
    kind VARCHAR(20) NOT NULL COMMENT 'PRESCRIPTION 또는 PILL',
    side VARCHAR(10) NULL COMMENT '알약 FRONT/BACK, 처방전 NULL',
    front_photo_id BIGINT NULL COMMENT '알약 뒷면이 참조하는 앞면 사진',
    temp_path VARCHAR(255) NOT NULL COMMENT '서버 임시 파일 경로; UUID 이름, 원래 파일명 미저장',
    mime_type VARCHAR(30) NOT NULL COMMENT '파일 내용 검사 후 저장한 MIME',
    size_bytes BIGINT NOT NULL COMMENT '파일 크기 바이트',
    status VARCHAR(20) NOT NULL COMMENT 'UPLOADED 또는 IDENTIFIED',
    uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '30분 만료 기준',
    CONSTRAINT pk_photo PRIMARY KEY (photo_id),
    CONSTRAINT uk_photo_front UNIQUE (front_photo_id),
    CONSTRAINT fk_photo_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_photo_profile FOREIGN KEY (profile_id) REFERENCES profile (profile_id),
    CONSTRAINT fk_photo_front FOREIGN KEY (front_photo_id) REFERENCES photo (photo_id) ON DELETE CASCADE,
    CONSTRAINT ck_photo_kind CHECK (kind IN ('PRESCRIPTION', 'PILL')),
    CONSTRAINT ck_photo_side CHECK (
        (kind = 'PRESCRIPTION' AND side IS NULL AND front_photo_id IS NULL) OR
        (kind = 'PILL' AND side IS NOT NULL AND side = 'FRONT' AND front_photo_id IS NULL) OR
        (kind = 'PILL' AND side IS NOT NULL AND side = 'BACK' AND front_photo_id IS NOT NULL)),
    CONSTRAINT ck_photo_status CHECK (status IN ('UPLOADED', 'IDENTIFIED')),
    CONSTRAINT ck_photo_size CHECK (size_bytes > 0),
    INDEX ix_photo_uploaded_at (uploaded_at)
) ENGINE=INNODB COMMENT '임시 사진; 등록 완료/취소/실패/만료 시 삭제';

CREATE TABLE IF NOT EXISTS ocr_candidate
(
    ocr_candidate_id BIGINT AUTO_INCREMENT COMMENT '사진 후보 코드',
    photo_id BIGINT NOT NULL COMMENT '처방전 사진 또는 알약 앞면 사진',
    read_no INT NOT NULL COMMENT '처방전 카드 1~10, 알약 1',
    read_name VARCHAR(255) NULL COMMENT '읽은 약 이름만; 처방전 전체 원문 미저장',
    item_seq VARCHAR(100) NULL COMMENT '공식 후보 약; 후보 없는 카드는 NULL',
    candidate_rank INT NOT NULL COMMENT '카드 안 후보 순위 1~5',
    lookup_status VARCHAR(10) NOT NULL COMMENT 'OK/NOT_FOUND/FAILED',
    print_matched BOOLEAN NULL COMMENT '알약 식별문자 일치 여부',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '후보 생성일시',
    CONSTRAINT pk_ocr_candidate PRIMARY KEY (ocr_candidate_id),
    CONSTRAINT uk_ocr_candidate_rank UNIQUE (photo_id, read_no, candidate_rank),
    CONSTRAINT uk_ocr_candidate_item UNIQUE (photo_id, read_no, item_seq),
    CONSTRAINT fk_candidate_photo FOREIGN KEY (photo_id) REFERENCES photo (photo_id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_drug FOREIGN KEY (item_seq) REFERENCES drug (item_seq),
    CONSTRAINT ck_candidate_read_no CHECK (read_no BETWEEN 1 AND 10),
    CONSTRAINT ck_candidate_rank CHECK (candidate_rank BETWEEN 1 AND 5),
    CONSTRAINT ck_candidate_lookup CHECK (
        (lookup_status = 'OK' AND item_seq IS NOT NULL) OR
        (lookup_status IN ('NOT_FOUND', 'FAILED') AND item_seq IS NULL AND candidate_rank = 1))
) ENGINE=INNODB COMMENT '사진별 약 후보; 사진 삭제 시 함께 삭제';

CREATE TABLE IF NOT EXISTS dur_warning
(
    dur_warning_id BIGINT AUTO_INCREMENT COMMENT 'DUR 주의사항 코드',
    item_seq VARCHAR(100) NOT NULL COMMENT '기준 약 품목기준코드',
    type VARCHAR(50) NOT NULL COMMENT 'PREGNANCY/AGE/ELDERLY/DOSE/PERIOD 등',
    ingredient VARCHAR(255) NULL COMMENT 'DUR 성분 코드/이름',
    effect VARCHAR(255) NULL COMMENT '효능군',
    content TEXT NULL COMMENT '공공데이터 금기/주의 내용',
    synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '공공데이터 조회 시각',
    CONSTRAINT pk_dur_warning PRIMARY KEY (dur_warning_id),
    CONSTRAINT fk_dur_warning_drug FOREIGN KEY (item_seq) REFERENCES drug (item_seq)
) ENGINE=INNODB COMMENT '품목별 공공데이터 DUR 주의사항';

CREATE TABLE IF NOT EXISTS dur_interaction
(
    dur_interaction_id BIGINT AUTO_INCREMENT COMMENT 'DUR 병용금기 코드',
    item_seq VARCHAR(100) NOT NULL COMMENT '기준 약 품목기준코드',
    mixture_item_seq VARCHAR(100) NOT NULL COMMENT '상대 약 코드; 로컬 drug에 없을 수 있어 FK 아님',
    mixture_name VARCHAR(255) NULL COMMENT '상대 약 이름',
    content TEXT NULL COMMENT '공공데이터 병용금기 내용',
    synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '공공데이터 조회 시각',
    CONSTRAINT pk_dur_interaction PRIMARY KEY (dur_interaction_id),
    CONSTRAINT uk_dur_interaction_pair UNIQUE (item_seq, mixture_item_seq),
    CONSTRAINT fk_dur_interaction_drug FOREIGN KEY (item_seq) REFERENCES drug (item_seq)
) ENGINE=INNODB COMMENT '공공데이터 DUR 병용금기';

CREATE TABLE IF NOT EXISTS push_subscription
(
    subscription_id BIGINT AUTO_INCREMENT COMMENT '브라우저 푸시 구독 코드',
    member_id BIGINT NOT NULL COMMENT '현재 구독 소유 회원',
    endpoint VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'URL은 ASCII 인코딩; 대소문자 구분',
    p256dh VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '브라우저 공개 키(base64url)',
    auth VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '브라우저 인증 키(base64url)',
    user_agent VARCHAR(500) NULL COMMENT '브라우저 정보',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '구독 생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 구독 갱신일시',
    CONSTRAINT pk_push_subscription PRIMARY KEY (subscription_id),
    CONSTRAINT uk_push_subscription_endpoint UNIQUE (endpoint),
    CONSTRAINT fk_push_subscription_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=INNODB COMMENT 'API 명세에 필요한 브라우저 웹 푸시 구독';
