-- V3-KAKAO-012~016(DEC-029): 카카오톡 약봉이를 웹 회원 계정에 잇는 연결 코드·연결 표와 상담방의 시작 경로.
-- 기존 DB에서 16 뒤 실행함. 최신01로 만든 DB에는 이미 같은 구조가 있음. 기존 표·값은 바꾸지 않고, 다시 실행해도 같은 결과임.
-- 연결 코드 원문·카카오 사용자 키 원문은 두지 않음. 코드는 SHA-256, 카카오 키는 다리가 만든 HMAC-SHA256 값(16진 64자)만 둠.
-- 한 연결은 카카오 사용자 하나와 회원 하나를 잇고, 그 회원의 모든 프로필 가운데 지금 보는 프로필 하나를 가리킴(9.2 Q4 B안).

-- 연결 코드. 회원이 웹에서 안내를 확인하고 받은 8자 코드(10분, 한 번). 새 코드를 받으면 앞 코드는 revoked_at이 생김.
CREATE TABLE IF NOT EXISTS kakao_link_code (
    code_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '연결 코드 고유 식별자',
    member_id BIGINT NOT NULL COMMENT '코드를 받은 회원',
    code_hash CHAR(64) NOT NULL COMMENT '대문자 8자(하이픈 없음)의 SHA-256 16진값',
    notice_version VARCHAR(20) NOT NULL COMMENT '확인한 연결 안내 판(예: KAKAO-LINK-1)',
    agreed_at DATETIME NOT NULL COMMENT '연결 안내를 확인한 시각',
    created_at DATETIME NOT NULL COMMENT '코드를 만든 시각',
    expires_at DATETIME NOT NULL COMMENT '만든 뒤 10분. 이 시각부터 쓸 수 없음',
    used_at DATETIME NULL COMMENT '카톡에서 연결에 쓴 시각',
    revoked_at DATETIME NULL COMMENT '새 코드를 받아 못 쓰게 된 시각',
    PRIMARY KEY (code_id),
    UNIQUE KEY uq_kakao_link_code_hash (code_hash),
    KEY ix_kakao_link_code_member (member_id, created_at),
    CONSTRAINT fk_kakao_link_code_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE,
    CONSTRAINT ck_kakao_link_code_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 연결. 카카오 사용자 하나는 연결 하나만 가짐. 회원은 연결 여러 개(최대 5, 서버가 막음)를 가질 수 있음.
-- 지금 보는 프로필이 지워지면 NULL이 되고, 다리는 다음 메시지 때 프로필을 다시 고르게 함.
CREATE TABLE IF NOT EXISTS kakao_link (
    link_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '연결 고유 식별자',
    member_id BIGINT NOT NULL COMMENT '연결된 회원',
    kakao_user_hmac CHAR(64) NOT NULL COMMENT '카카오 사용자 키의 다리 HMAC-SHA256 16진값',
    current_profile_id BIGINT NULL COMMENT '카톡에서 지금 보는 프로필. 고르기 전이거나 지워지면 NULL',
    notice_version VARCHAR(20) NOT NULL COMMENT '연결 코드를 받을 때 확인한 안내 판',
    agreed_at DATETIME NOT NULL COMMENT '연결 안내를 확인한 시각',
    linked_at DATETIME NOT NULL COMMENT '카톡에서 코드를 보내 연결한 시각',
    last_used_at DATETIME NULL COMMENT '다리가 이 연결로 마지막으로 부른 시각(1분 단위로 갱신)',
    PRIMARY KEY (link_id),
    UNIQUE KEY uq_kakao_link_user (kakao_user_hmac),
    KEY ix_kakao_link_member (member_id, linked_at),
    KEY fk_kakao_link_profile (current_profile_id),
    CONSTRAINT fk_kakao_link_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE,
    CONSTRAINT fk_kakao_link_profile FOREIGN KEY (current_profile_id)
        REFERENCES profile (profile_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 상담방을 연 곳(9.2 Q5 B안). 기존 행은 모두 웹에서 연 것으로 봄.
SET @chat_session_channel_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='chat_session' AND column_name='channel')=0,
    'ALTER TABLE chat_session ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT ''WEB'' COMMENT ''상담방을 연 곳 WEB / KAKAO''', 'SELECT 1');
PREPARE chat_session_channel_stmt FROM @chat_session_channel_ddl;
EXECUTE chat_session_channel_stmt;
DEALLOCATE PREPARE chat_session_channel_stmt;

SET @chat_session_channel_check = IF((SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema=DATABASE() AND table_name='chat_session' AND constraint_name='ck_chat_session_channel')=0,
    'ALTER TABLE chat_session ADD CONSTRAINT ck_chat_session_channel CHECK (channel IN (''WEB'',''KAKAO''))', 'SELECT 1');
PREPARE chat_session_channel_check_stmt FROM @chat_session_channel_check;
EXECUTE chat_session_channel_check_stmt;
DEALLOCATE PREPARE chat_session_channel_check_stmt;

SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='kakao_link_code')=9
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='kakao_link')=8
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_session'
        AND column_name='channel' AND data_type='varchar' AND character_maximum_length=10 AND is_nullable='NO'
        AND column_default='WEB')=1
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name IN ('kakao_link_code','kakao_link') AND constraint_type='FOREIGN KEY')=3
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name IN ('kakao_link_code','kakao_link') AND constraint_type='UNIQUE')=2
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND ((table_name='kakao_link_code' AND constraint_name='ck_kakao_link_code_expiry')
          OR (table_name='chat_session' AND constraint_name='ck_chat_session_channel'))
        AND constraint_type='CHECK' AND enforced='YES')=2,
    'READY','BLOCKED') AS kakao_link_status;
