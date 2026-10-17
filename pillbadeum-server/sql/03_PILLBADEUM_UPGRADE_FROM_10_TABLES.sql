-- 기존 aa15bf7 원본 10개 테이블 DB를 위한 1회 업그레이드입니다.
-- 신규 DB에는 실행하지 마세요. 먼저 백업하고 앱/다른 DB 작성 작업을 멈춘 상태에서 실행하세요.
-- mysql --database=대상DB < sql/03_PILLBADEUM_UPGRADE_FROM_10_TABLES.sql
-- --force 금지: 사전검사 실패 시 멈춰야 합니다. MySQL DDL은 전체 트랜잭션 롤백이 되지 않습니다.
-- 01 재실행으로 기존 스키마가 바뀌지는 않습니다. 아래 ALTER가 그 차이를 반영합니다.
-- 검사 실패 시 자동 데이터 삭제/중복 병합을 하지 않습니다. SELECT로 대상을 확인하고 팀에서 기준을 정하세요.

CREATE TEMPORARY TABLE pill_config_upgrade_preflight
(
    check_name VARCHAR(100) NOT NULL,
    problem_count BIGINT NOT NULL,
    CONSTRAINT ck_pill_config_upgrade_preflight CHECK (problem_count = 0)
);

-- 원본의 턴 UNIQUE가 있어야 합니다. 이미 새 구조면 재실행하지 마세요.
INSERT INTO pill_config_upgrade_preflight
SELECT 'legacy_message_unique_required', IF(COUNT(*) = 2, 0, 1)
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'chat_message'
  AND index_name = 'uk_chat_message_session_turn' AND non_unique = 0;

-- 새 컬럼이 이미 있으면 부분 적용 여부를 확인합니다. 자동으로 다음 단계를 추측하지 않습니다.
INSERT INTO pill_config_upgrade_preflight
SELECT 'new_columns_must_not_exist', COUNT(*)
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'profile' AND column_name = 'color')
    OR (table_name = 'drug' AND column_name IN
        ('color_back', 'appearance', 'len_long', 'len_short', 'main_ingredient', 'cancel_status', 'dur_synced_at')));

-- 같은 프로필/약이 여러 행이면 UNIQUE 추가 전에 어느 일정/행을 유지할지 결정해야 합니다.
-- 확인: SELECT profile_id,item_seq,COUNT(*) FROM my_drug GROUP BY profile_id,item_seq HAVING COUNT(*)>1;
INSERT INTO pill_config_upgrade_preflight
SELECT 'duplicate_my_drug', COUNT(*) FROM
    (SELECT profile_id, item_seq FROM my_drug GROUP BY profile_id, item_seq HAVING COUNT(*) > 1) duplicates;

-- 원본 데이터는 메시지 단위 턴(1 USER,2 ASSISTANT,3 USER,4 ASSISTANT)입니다.
-- 아래는 1부터 연속되고 역할이 교대로 나오는 원본 형식만 변환합니다(마지막 미응답 USER는 허용).
-- 다른 패턴이면 자동 연결하지 말고 메시지/요약을 확인하세요.
INSERT INTO pill_config_upgrade_preflight
SELECT 'legacy_message_role_order', COUNT(*) FROM chat_message
WHERE turn_no < 1 OR role <> IF(MOD(turn_no, 2) = 1, 'USER', 'ASSISTANT');
INSERT INTO pill_config_upgrade_preflight
SELECT 'legacy_message_gaps', COUNT(*) FROM
    (SELECT session_id FROM chat_message GROUP BY session_id
     HAVING MIN(turn_no) <> 1 OR MAX(turn_no) <> COUNT(*)) gaps;

-- 요약은 완성된 질문/답변 쌍의 경계만 허용합니다. 임의 범위를 추정해서 고치지 않습니다.
INSERT INTO pill_config_upgrade_preflight
SELECT 'legacy_summary_boundaries', COUNT(*) FROM chat_summary s
WHERE MOD(s.from_turn, 2) <> 1 OR MOD(s.to_turn, 2) <> 0
   OR s.from_turn < 1 OR s.to_turn < s.from_turn
   OR s.to_turn > COALESCE((SELECT MAX(m.turn_no) FROM chat_message m WHERE m.session_id = s.session_id), 0);
-- 확인: SELECT session_id,from_turn,COUNT(*) FROM chat_summary GROUP BY session_id,from_turn HAVING COUNT(*)>1;
INSERT INTO pill_config_upgrade_preflight
SELECT 'duplicate_summary_start', COUNT(*) FROM
    (SELECT session_id, from_turn FROM chat_summary GROUP BY session_id, from_turn HAVING COUNT(*) > 1) duplicates;

-- 여기까지 모두 통과한 뒤 영구 스키마/데이터를 변경합니다.
ALTER TABLE member MODIFY email VARCHAR(100) NOT NULL COMMENT '이메일';
ALTER TABLE profile
    MODIFY profile_pregnant BOOLEAN NULL DEFAULT NULL COMMENT 'TRUE 예 / FALSE 아니오 / NULL 미입력',
    ADD color CHAR(7) NULL COMMENT '서버가 배정한 프로필 색 #RRGGBB';
-- 기존 FALSE를 unknown으로 추정하여 덮어쓰지 않습니다. 새 요청부터 NULL을 구분해 저장합니다.
ALTER TABLE drug
    MODIFY maker VARCHAR(100) NULL COMMENT '제조사',
    MODIFY etc_otc VARCHAR(10) NULL COMMENT '전문/일반 구분',
    MODIFY class_name VARCHAR(100) NULL COMMENT '분류명',
    MODIFY shape VARCHAR(50) NULL COMMENT '모양',
    MODIFY color VARCHAR(50) NULL COMMENT '앞면 색상(API colorFront)',
    MODIFY form VARCHAR(30) NULL COMMENT '제형',
    MODIFY material TEXT NULL COMMENT '허가정보 원료성분',
    ADD color_back VARCHAR(50) NULL COMMENT '뒷면 색상',
    ADD appearance TEXT NULL COMMENT '공식 성상',
    ADD len_long DECIMAL(10,3) NULL COMMENT '장축 mm',
    ADD len_short DECIMAL(10,3) NULL COMMENT '단축 mm',
    ADD main_ingredient TEXT NULL COMMENT '허가정보 유효성분',
    ADD cancel_status VARCHAR(30) NULL COMMENT '공공데이터 허가 취하 상태',
    ADD dur_synced_at DATETIME NULL COMMENT 'DUR 조회 성공 시각';
ALTER TABLE my_drug
    MODIFY dose_per_time INT NULL COMMENT '1회 복용량; 일정 등록 전 NULL',
    MODIFY start_date DATE NULL COMMENT '복용 시작일; 일정 등록 전 NULL',
    MODIFY end_date DATE NULL COMMENT '복용 종료일; 일정 등록 전 NULL',
    MODIFY dose_times VARCHAR(50) NULL COMMENT '하루 복용 시각; 일정 등록 전 NULL',
    ADD CONSTRAINT uk_my_drug_profile_item UNIQUE (profile_id, item_seq);
ALTER TABLE chat_session
    MODIFY snap_birth_year INT NULL COMMENT '첫 질문 시 프로필 출생년도 사본',
    MODIFY snap_gender VARCHAR(10) NULL COMMENT '첫 질문 시 프로필 성별 사본';

-- 기존 순서를 보존하며 메시지 번호 1/2 -> 턴1, 3/4 -> 턴2로 정규화합니다.
-- 본문/역할/ID/시각은 바꾸지 않습니다. Qdrant payload에 turn_no를 저장했다면 재색인해야 합니다.
ALTER TABLE chat_message ADD CONSTRAINT uk_chat_message_session_turn_role UNIQUE (session_id, turn_no, role);
ALTER TABLE chat_message DROP INDEX uk_chat_message_session_turn;
UPDATE chat_message SET turn_no = (turn_no + 1) DIV 2 ORDER BY turn_no ASC;
ALTER TABLE chat_summary ADD CONSTRAINT uk_chat_summary_start UNIQUE (session_id, from_turn);
ALTER TABLE chat_summary DROP INDEX uk_chat_summary_range;
UPDATE chat_summary SET from_turn = (from_turn + 1) DIV 2, to_turn = to_turn DIV 2 ORDER BY from_turn ASC;

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

-- 기존 테이블/행은 유지됩니다. 이 임시 사전검사 테이블만 정리합니다.
DROP TEMPORARY TABLE pill_config_upgrade_preflight;
