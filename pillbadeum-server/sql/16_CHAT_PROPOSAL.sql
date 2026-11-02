-- V3-CHAT-002~005·007: 대화 속 등록 제안·일정 제안을 상담 메시지 JSON이 아닌 별도 표에 둠(H-10).
-- 기존 DB에서 14 뒤 실행함. 최신01로 만든 DB에는 이미 같은 구조가 있음. 기존 표·값은 바꾸지 않고, 다시 실행해도 같은 결과임.
-- my_drug.source의 새 값 CHAT('대화')은 기존 varchar(30)에 들어가므로 구조 변경이 없음.
-- 상태는 대기(PENDING)에서 확정·취소·만료로 한 번만 감. 서버는 "대기이고 만료 전"인 행만 바꾸는 조건부 UPDATE 한 줄로
-- 상태를 바꾸고, 바뀐 행이 0이면 409로 답함. 동시에 두 번 확정하면 InnoDB 행 잠금 때문에 뒤 요청은 바뀐 행이 0임.
-- 확정으로 만든 약목록 약은 항목마다 하나(uq_chat_proposal_item_my_drug)이고, 같은 프로필·품목은 my_drug의 UNIQUE가 다시 막음.

-- 제안 하나. 카드를 보여 주는 약봉이 메시지와 1:1이며, 메시지·프로필·회원이 지워지면 함께 지워짐.
CREATE TABLE IF NOT EXISTS chat_proposal (
    proposal_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '제안 고유 식별자',
    member_id BIGINT NOT NULL COMMENT '제안을 받은 회원',
    profile_id BIGINT NOT NULL COMMENT '제안 대상 프로필',
    message_id BIGINT NOT NULL COMMENT '제안 카드를 보여 주는 약봉이 메시지',
    kind VARCHAR(12) NOT NULL COMMENT 'REGISTER(등록 제안) / SCHEDULE(일정 제안)',
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CONFIRMED / CANCELLED / EXPIRED',
    created_at DATETIME NOT NULL COMMENT '제안을 만든 시각',
    expires_at DATETIME NOT NULL COMMENT '만든 뒤 30분. 이 시각부터 확정할 수 없음',
    decided_at DATETIME NULL COMMENT '확정·취소·만료로 바뀐 시각. 대기 중에는 NULL',
    PRIMARY KEY (proposal_id),
    UNIQUE KEY uq_chat_proposal_message (message_id),
    KEY ix_chat_proposal_owner (member_id, profile_id, created_at),
    KEY ix_chat_proposal_pending (status, expires_at),
    KEY fk_chat_proposal_profile (profile_id),
    CONSTRAINT fk_chat_proposal_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_proposal_profile FOREIGN KEY (profile_id)
        REFERENCES profile (profile_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_proposal_message FOREIGN KEY (message_id)
        REFERENCES chat_message (message_id) ON DELETE CASCADE,
    CONSTRAINT ck_chat_proposal_kind CHECK (kind IN ('REGISTER','SCHEDULE')),
    CONSTRAINT ck_chat_proposal_status CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_chat_proposal_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_chat_proposal_decided CHECK ((status = 'PENDING' AND decided_at IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 등록 제안의 항목(카드 한 줄 = 공공데이터 후보 하나, 최대 10개). 확정 때 고른 항목과 만든 약목록 약을 남겨
-- 다시 확정하면 처음 결과를 돌려줌. 약목록 약을 지우면 my_drug_id만 NULL이 되고 고른 기록은 남음.
CREATE TABLE IF NOT EXISTS chat_proposal_item (
    proposal_id BIGINT NOT NULL COMMENT '등록 제안',
    item_no TINYINT UNSIGNED NOT NULL COMMENT '카드 안 순서 1~10',
    item_seq VARCHAR(100) NOT NULL COMMENT '후보 품목기준코드',
    mention VARCHAR(100) NULL COMMENT '대화·사진에서 읽은 이름·함량 원문',
    strength_match VARCHAR(10) NOT NULL COMMENT 'MATCH / DIFFERENT / UNKNOWN(V3-REG-002)',
    already_registered TINYINT(1) NOT NULL DEFAULT 0 COMMENT '만들 때 이미 약목록에 있어 고를 수 없음',
    chosen TINYINT(1) NOT NULL DEFAULT 0 COMMENT '확정 때 고른 항목',
    my_drug_id BIGINT NULL COMMENT '확정으로 만든 약목록 약. 그 약을 지우면 NULL',
    PRIMARY KEY (proposal_id, item_no),
    UNIQUE KEY uq_chat_proposal_item_drug (proposal_id, item_seq),
    UNIQUE KEY uq_chat_proposal_item_my_drug (my_drug_id),
    KEY fk_chat_proposal_item_drug (item_seq),
    CONSTRAINT fk_chat_proposal_item_proposal FOREIGN KEY (proposal_id)
        REFERENCES chat_proposal (proposal_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_proposal_item_drug FOREIGN KEY (item_seq)
        REFERENCES drug (item_seq),
    CONSTRAINT fk_chat_proposal_item_my_drug FOREIGN KEY (my_drug_id)
        REFERENCES my_drug (my_drug_id) ON DELETE SET NULL,
    CONSTRAINT ck_chat_proposal_item_no CHECK (item_no BETWEEN 1 AND 10),
    CONSTRAINT ck_chat_proposal_item_strength CHECK (strength_match IN ('MATCH','DIFFERENT','UNKNOWN')),
    CONSTRAINT ck_chat_proposal_item_choice CHECK (NOT (chosen = 1 AND already_registered = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 일정 제안의 내용(제안 하나에 하나). 확정 때 대상 약의 지금 일정이 current_schedule과 다르면 확정하지 않음.
-- 대상 약을 지우면 my_drug_id가 NULL이 되고 그 제안은 확정할 수 없음. JSON 칸 모양은 V3-CHAT-007 구현에서 정함.
CREATE TABLE IF NOT EXISTS chat_proposal_schedule (
    proposal_id BIGINT NOT NULL COMMENT '일정 제안',
    my_drug_id BIGINT NULL COMMENT '일정을 바꿀 약목록 약. 그 약을 지우면 NULL',
    current_schedule JSON NOT NULL COMMENT '만들 때의 일정(카드의 지금)',
    proposed_schedule JSON NOT NULL COMMENT '해석한 일정(카드의 바뀐 뒤)',
    PRIMARY KEY (proposal_id),
    KEY fk_chat_proposal_schedule_my_drug (my_drug_id),
    CONSTRAINT fk_chat_proposal_schedule_proposal FOREIGN KEY (proposal_id)
        REFERENCES chat_proposal (proposal_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_proposal_schedule_my_drug FOREIGN KEY (my_drug_id)
        REFERENCES my_drug (my_drug_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_proposal')=9
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_proposal_item')=8
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_proposal_schedule')=4
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name IN ('chat_proposal','chat_proposal_item','chat_proposal_schedule') AND constraint_type='FOREIGN KEY')=8
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name IN ('chat_proposal','chat_proposal_item') AND constraint_type='CHECK' AND enforced='YES')=7
    AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name IN ('chat_proposal','chat_proposal_item') AND constraint_type='UNIQUE')=3,
    'READY','BLOCKED') AS chat_proposal_status;
