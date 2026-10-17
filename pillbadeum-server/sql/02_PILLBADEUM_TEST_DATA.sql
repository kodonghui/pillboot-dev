-- 선택 실행: 테이블만 만든 비어 있는 개발 DB에 한 번만 실행하세요.
-- 실제 사용자 DB에는 실행하지 않습니다. 로그인 API는 아직 구현되지 않았습니다.
-- 약 item_seq와 약 이름/제조사의 조합은 공식 검증되지 않은 화면/DB용 fixture입니다.
-- 실제 공공데이터 호출 테스트에는 공식 응답으로 검증한 코드로 별도 준비하세요.
-- PHOTO/OCR_CANDIDATE/DUR_WARNING/DUR_INTERACTION은 실제 처리 시 생성하므로 초기에는 비웁니다.
-- ERD golden의 DRUG.etc_otc UNIQUE를 유지합니다. 같은 분류의 약을 여러 개 저장할 수 없으므로
-- OTC 1개·ETC 1개만 넣습니다. 실제 의약품 여러 개를 저장하기 전 ERD 제약의 별도 확인이 필요합니다.

START TRANSACTION;

-- 3-1. 회원 5명 (원본 BCrypt 해시 예시 유지; 평문 비밀번호 검증 전 로그인 fixture로 사용 금지)
INSERT INTO MEMBER (email, password_hash, nickname, user_role, created_at, updated_at) VALUES
('minji@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '김민지', 'ROLE_USER', '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
('junho@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '이준호', 'ROLE_USER', '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
('seoyeon@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '박서연', 'ROLE_USER', '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
('hyunwoo@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '최현우', 'ROLE_USER', '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
('admin@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '필바다움관리자', 'ROLE_ADMIN', '2026-10-05 13:00:00', '2026-10-05 13:00:00');

-- 3-2. 약관 4종: 개인정보/민감정보/마케팅/복약 알림 수신 동의
INSERT INTO CONSENT (terms_type, terms_version, terms_title, terms_content, is_required, is_active, effective_at, created_at) VALUES
('PERSONAL_INFO', '1.0', '개인정보 수집 및 이용 동의', '서비스 제공과 회원 관리에 필요한 개인정보를 수집 및 이용합니다.', TRUE, TRUE, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
('SENSITIVE_INFO', '1.0', '민감정보 수집 및 이용 동의', '건강 및 복약 관련 민감정보를 복약 관리와 상담 서비스에 이용합니다.', TRUE, TRUE, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
('MARKETING', '1.0', '마케팅 정보 수신 동의', '이벤트와 혜택 정보를 전송할 수 있습니다.', FALSE, TRUE, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
('MEDICATION_REMINDER', '1.0', '복약 알림 수신 동의', '등록된 복약 시간에 맞추어 알림을 발송합니다.', FALSE, TRUE, '2026-10-01 00:00:00', '2026-10-01 00:00:00');

-- 3-3. 회원당 본인·가족 프로필 3개, 총 15개
INSERT INTO PROFILE (member_id, profile_name, realationship_type, profile_birth_year, profile_gender, profile_height, profile_weight, profile_pregnant, created_at, updated_at) VALUES
(1, '민지', 'SELF', 1995, 'FEMALE', 163, 52, FALSE, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
(1, '엄마', 'MOTHER', 1968, 'FEMALE', 158, 57, FALSE, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
(1, '아들 도윤', 'CHILD', 2020, 'MALE', 105, 17, FALSE, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
(2, '준호', 'SELF', 1990, 'MALE', 178, 74, FALSE, '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
(2, '아버지', 'FATHER', 1962, 'MALE', 171, 70, FALSE, '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
(2, '딸 지아', 'CHILD', 2018, 'FEMALE', 116, 21, FALSE, '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
(3, '서연', 'SELF', 1993, 'FEMALE', 167, 55, FALSE, '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
(3, '할머니', 'FAMILY', 1945, 'FEMALE', 150, 48, FALSE, '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
(3, '남편', 'SPOUSE', 1991, 'MALE', 180, 78, FALSE, '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
(4, '현우', 'SELF', 1988, 'MALE', 175, 69, FALSE, '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
(4, '어머니', 'MOTHER', 1965, 'FEMALE', 155, 54, FALSE, '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
(4, '아들 민석', 'CHILD', 2016, 'MALE', 124, 25, FALSE, '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
(5, '관리자', 'SELF', 1985, 'UNKNOWN', NULL, NULL, FALSE, '2026-10-05 13:00:00', '2026-10-05 13:00:00'),
(5, '부모님', 'PARENT', 1958, 'UNKNOWN', NULL, NULL, FALSE, '2026-10-05 13:00:00', '2026-10-05 13:00:00'),
(5, '테스트 자녀', 'CHILD', 2015, 'UNKNOWN', NULL, NULL, FALSE, '2026-10-05 13:00:00', '2026-10-05 13:00:00');

-- 3-4. 약관 동의 이력 3건
INSERT INTO CONSENT_HISTORY (member_id, terms_id, consent_status, recorded_at) VALUES
(1, 1, 'AGREED', '2026-10-01 09:02:00'),
(1, 2, 'AGREED', '2026-10-01 09:02:10'),
(2, 3, 'WITHDRAWN', '2026-10-08 18:30:00');

-- 3-5. 알림 설정: 회원 1은 켬, 회원 2는 끔
INSERT INTO notification_setting (member_id, push_enabled, updated_at) VALUES
(1, TRUE, '2026-10-10 08:00:00'),
(2, FALSE, '2026-10-10 08:05:00');

-- 3-6. ERD의 etc_otc UNIQUE에 맞춘 의약품 2개(분류값 자체를 가짜 고유값으로 바꾸지 않음)
INSERT INTO DRUG (item_seq, name, maker, etc_otc, class_name, shape, color, form, matrial, synced_at) VALUES
('202600001', '타이레놀정500밀리그램', '한국얀센', 'OTC', '해열·진통·소염제', '장방형', '흰색', '정제', '아세트아미노펜', '2026-10-10 00:00:00'),
('202600004', '아모디핀정5밀리그램', '한미약품', 'ETC', '혈압강하제', '원형', '흰색', '정제', '암로디핀베실산염', '2026-10-10 00:00:00');

-- 3-7. 프로필 15개 각각에 내 약 2개씩, 총 30개
INSERT INTO my_drug (profile_id, item_seq, dose_per_time, timing, start_date, end_date, dose_times, source, registered_at, updated_at)
SELECT p.profile_id, d.item_seq, 1,
       CASE d.seq WHEN 1 THEN '식후 30분' ELSE '취침 전' END,
       '2026-10-01', '2026-12-31',
       CASE d.seq WHEN 1 THEN '08:00' ELSE '21:00' END,
       'SEARCH', '2026-10-10 09:00:00', '2026-10-10 09:00:00'
FROM PROFILE p
CROSS JOIN (
    SELECT 1 AS seq, '202600001' AS item_seq UNION ALL
    SELECT 2, '202600004'
) d;

-- 3-8. 프로필별 상담 세션 3개, 총 45개
INSERT INTO chat_session (member_id, profile_id, title, last_response_id, created_at, updated_at, snap_birth_year, snap_gender, snap_pregnant)
SELECT p.member_id, p.profile_id,
       CONCAT(p.profile_name, ' ', s.topic, ' 상담'),
       CONCAT('resp_seed_', p.profile_id, '_', s.session_no),
       DATE_ADD('2026-10-11 09:00:00', INTERVAL (p.profile_id * 3 + s.session_no) HOUR),
       DATE_ADD('2026-10-11 09:10:00', INTERVAL (p.profile_id * 3 + s.session_no) HOUR),
       p.profile_birth_year, p.profile_gender,
       CASE WHEN p.profile_pregnant THEN 'TRUE' ELSE 'FALSE' END
FROM PROFILE p
CROSS JOIN (
    SELECT 1 AS session_no, '복용 방법' AS topic UNION ALL
    SELECT 2, '병용 가능 여부' UNION ALL
    SELECT 3, '부작용'
) s;

-- 각 세션에 2턴 × 사용자·AI 메시지 2개, 총 180개
INSERT INTO chat_message (session_id, trun_no, role, content, token_count, created_at)
SELECT cs.session_id, m.trun_no, m.role,
       CASE m.message_order
           WHEN 1 THEN CONCAT(cs.title, '에 대해 알려주세요.')
           WHEN 2 THEN '등록된 약과 프로필 정보를 기준으로 일반적인 복약 안내를 드립니다. 처방 변경은 의료진과 상의하세요.'
           WHEN 3 THEN '복용할 때 주의할 점도 알려주세요.'
           ELSE '이상 반응이 있거나 증상이 지속되면 복용을 중단하고 의사 또는 약사에게 상담하세요.'
       END,
       CASE WHEN m.role = 'user' THEN 18 ELSE 42 END,
       DATE_ADD(cs.created_at, INTERVAL m.message_order MINUTE)
FROM chat_session cs
CROSS JOIN (
    SELECT 1 AS message_order, 1 AS trun_no, 'user' AS role UNION ALL
    SELECT 2, 1, 'assistant' UNION ALL
    SELECT 3, 2, 'user' UNION ALL
    SELECT 4, 2, 'assistant'
) m;

-- 각 세션에 요약 1개씩, 총 45개
INSERT INTO chat_summary (session_id, from_turn, to_turn, content, created_at)
SELECT session_id, 1, 2,
       CONCAT(title, ' 대화에서 복용 방법과 주의사항을 안내했으며, 이상 반응 시 전문가 상담이 필요함을 고지했다.'),
       DATE_ADD(updated_at, INTERVAL 1 MINUTE)
FROM chat_session;

COMMIT;

-- 생성 결과 확인
SELECT 'MEMBER' AS table_name, COUNT(*) AS row_count FROM MEMBER
UNION ALL SELECT 'PROFILE', COUNT(*) FROM PROFILE
UNION ALL SELECT 'CONSENT', COUNT(*) FROM CONSENT
UNION ALL SELECT 'CONSENT_HISTORY', COUNT(*) FROM CONSENT_HISTORY
UNION ALL SELECT 'DRUG', COUNT(*) FROM DRUG
UNION ALL SELECT 'notification_setting', COUNT(*) FROM notification_setting
UNION ALL SELECT 'my_drug', COUNT(*) FROM my_drug
UNION ALL SELECT 'chat_session', COUNT(*) FROM chat_session
UNION ALL SELECT 'chat_message', COUNT(*) FROM chat_message
UNION ALL SELECT 'chat_summary', COUNT(*) FROM chat_summary
UNION ALL SELECT 'PHOTO', COUNT(*) FROM PHOTO
UNION ALL SELECT 'OCR_CANDIDATE', COUNT(*) FROM OCR_CANDIDATE
UNION ALL SELECT 'DUR_WARNING', COUNT(*) FROM DUR_WARNING
UNION ALL SELECT 'DUR_INTERACTION', COUNT(*) FROM DUR_INTERACTION;
