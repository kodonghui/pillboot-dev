-- REG-007: 낱알 결과와 사진 없는 직접 보완 검색의 30분 임시 저장임.
-- 01→03→04→05→06→07→08→09 뒤 대상 DB에 명시적으로 적용함.
-- 기존 테이블의 컬럼·값을 바꾸지 않음. 롤백 시 이 표를 즉시 삭제하지 않음.
CREATE TABLE IF NOT EXISTS pill_identification_session (
    identification_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    photo_id BIGINT NULL,
    origin VARCHAR(12) NOT NULL,
    lifecycle VARCHAR(12) NOT NULL,
    policy_version SMALLINT UNSIGNED NOT NULL,
    snapshot JSON NULL,
    selected_item_seq VARCHAR(100) NULL,
    registration_receipt JSON NULL,
    replacement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    PRIMARY KEY (identification_id),
    UNIQUE KEY uq_pill_identification_photo (photo_id),
    KEY ix_pill_identification_expiry (expires_at),
    KEY ix_pill_identification_owner (member_id, profile_id, created_at),
    CONSTRAINT fk_pill_identification_member FOREIGN KEY (member_id)
        REFERENCES member (member_id) ON DELETE CASCADE,
    CONSTRAINT fk_pill_identification_profile FOREIGN KEY (profile_id)
        REFERENCES profile (profile_id) ON DELETE CASCADE,
    CONSTRAINT fk_pill_identification_photo FOREIGN KEY (photo_id)
        REFERENCES photo (photo_id) ON DELETE SET NULL,
    CONSTRAINT ck_pill_identification_origin CHECK (origin IN ('PHOTO','CLUES')),
    CONSTRAINT ck_pill_identification_lifecycle CHECK (lifecycle IN ('ACTIVE','REPLACED','CANCELLED','CONSUMED')),
    CONSTRAINT ck_pill_identification_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB;
