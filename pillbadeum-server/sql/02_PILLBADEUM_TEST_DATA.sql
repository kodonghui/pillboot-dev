-- 선택 실행: 테이블만 만든 비어 있는 개발 DB에 한 번만 실행하세요.
-- 최신01·13 적용 뒤 빈 개발DB에서만 실행함. 실제 사용자DB와 검증 전 로그인 fixture에는 사용하지 않음.
-- 약 item_seq와 약 이름/제조사의 조합은 공식 검증되지 않은 화면/DB용 fixture입니다.
-- 실제 공공데이터 호출 테스트에는 공식 응답으로 검증한 코드로 별도 준비하세요.
-- photo/ocr_candidate/dur_warning/dur_interaction은 실제 처리 시 생성하므로 초기에는 비웁니다.
-- drug.etc_otc는 ETC(전문의약품) 또는 OTC(일반의약품)로 저장합니다.
-- 같은 분류의 약도 여러 개 저장할 수 있으며, CHECK 제약으로 다른 분류값은 제한합니다.

START TRANSACTION;

-- 3-1. 회원 5명 (원본 BCrypt 해시 예시 유지; 평문 비밀번호 검증 전 로그인 fixture로 사용 금지)
INSERT INTO member (email, password_hash, nickname, user_role, created_at, updated_at) VALUES
('minji@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '김민지', 'ROLE_USER', '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
('junho@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '이준호', 'ROLE_USER', '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
('seoyeon@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '박서연', 'ROLE_USER', '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
('hyunwoo@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '최현우', 'ROLE_USER', '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
('admin@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '필바다움관리자', 'ROLE_ADMIN', '2026-10-05 13:00:00', '2026-10-05 13:00:00');

-- 3-2. 가입 기본 약관은13_BASE_CONSENT.sql에서 먼저 준비함. 샘플로 중복 삽입하지 않음.

-- 3-3. 회원당 본인·가족 프로필 3개, 총 15개
-- 자동 증가 시작값과 관계없이 이메일로 방금 넣은 회원을 연결합니다.
INSERT INTO profile (member_id, profile_name, relationship_type, profile_birth_year, profile_gender, profile_height, profile_weight, profile_pregnant, created_at, updated_at) VALUES
((SELECT member_id FROM member WHERE email = 'minji@pillbadeum.com'), '민지', 'SELF', 1995, 'FEMALE', 163, 52, FALSE, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
((SELECT member_id FROM member WHERE email = 'minji@pillbadeum.com'), '엄마', 'MOTHER', 1968, 'FEMALE', 158, 57, FALSE, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
((SELECT member_id FROM member WHERE email = 'minji@pillbadeum.com'), '아들 도윤', 'CHILD', 2020, 'MALE', 105, 17, FALSE, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
((SELECT member_id FROM member WHERE email = 'junho@pillbadeum.com'), '준호', 'SELF', 1990, 'MALE', 178, 74, FALSE, '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
((SELECT member_id FROM member WHERE email = 'junho@pillbadeum.com'), '아버지', 'FATHER', 1962, 'MALE', 171, 70, FALSE, '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
((SELECT member_id FROM member WHERE email = 'junho@pillbadeum.com'), '딸 지아', 'CHILD', 2018, 'FEMALE', 116, 21, FALSE, '2026-10-02 10:00:00', '2026-10-02 10:00:00'),
((SELECT member_id FROM member WHERE email = 'seoyeon@pillbadeum.com'), '서연', 'SELF', 1993, 'FEMALE', 167, 55, FALSE, '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
((SELECT member_id FROM member WHERE email = 'seoyeon@pillbadeum.com'), '할머니', 'FAMILY', 1945, 'FEMALE', 150, 48, FALSE, '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
((SELECT member_id FROM member WHERE email = 'seoyeon@pillbadeum.com'), '남편', 'SPOUSE', 1991, 'MALE', 180, 78, FALSE, '2026-10-03 11:00:00', '2026-10-03 11:00:00'),
((SELECT member_id FROM member WHERE email = 'hyunwoo@pillbadeum.com'), '현우', 'SELF', 1988, 'MALE', 175, 69, FALSE, '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
((SELECT member_id FROM member WHERE email = 'hyunwoo@pillbadeum.com'), '어머니', 'MOTHER', 1965, 'FEMALE', 155, 54, FALSE, '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
((SELECT member_id FROM member WHERE email = 'hyunwoo@pillbadeum.com'), '아들 민석', 'CHILD', 2016, 'MALE', 124, 25, FALSE, '2026-10-04 12:00:00', '2026-10-04 12:00:00'),
((SELECT member_id FROM member WHERE email = 'admin@pillbadeum.com'), '관리자', 'SELF', 1985, 'UNKNOWN', NULL, NULL, FALSE, '2026-10-05 13:00:00', '2026-10-05 13:00:00'),
((SELECT member_id FROM member WHERE email = 'admin@pillbadeum.com'), '부모님', 'PARENT', 1958, 'UNKNOWN', NULL, NULL, FALSE, '2026-10-05 13:00:00', '2026-10-05 13:00:00'),
((SELECT member_id FROM member WHERE email = 'admin@pillbadeum.com'), '테스트 자녀', 'CHILD', 2015, 'UNKNOWN', NULL, NULL, FALSE, '2026-10-05 13:00:00', '2026-10-05 13:00:00');

-- 3-4. 약관 동의 이력 3건: 이메일·약관 종류·버전으로 샘플을 연결
INSERT INTO consent_history (member_id, terms_id, consent_status, recorded_at) VALUES
((SELECT member_id FROM member WHERE email = 'minji@pillbadeum.com'),
 (SELECT terms_id FROM consent WHERE terms_type = 'PERSONAL_INFO' AND terms_version = '1.0'),
 'AGREED', '2026-10-01 09:02:00'),
((SELECT member_id FROM member WHERE email = 'minji@pillbadeum.com'),
 (SELECT terms_id FROM consent WHERE terms_type = 'SENSITIVE_INFO' AND terms_version = '1.0'),
 'AGREED', '2026-10-01 09:02:10'),
((SELECT member_id FROM member WHERE email = 'junho@pillbadeum.com'),
 (SELECT terms_id FROM consent WHERE terms_type = 'MARKETING' AND terms_version = '1.0'),
 'WITHDRAWN', '2026-10-08 18:30:00');

-- 3-5. 알림 설정: 민지는 켬, 준호는 끔
INSERT INTO notification_setting (member_id, push_enabled, updated_at) VALUES
((SELECT member_id FROM member WHERE email = 'minji@pillbadeum.com'), TRUE, '2026-10-10 08:00:00'),
((SELECT member_id FROM member WHERE email = 'junho@pillbadeum.com'), FALSE, '2026-10-10 08:05:00');

-- 3-6. 의약품 샘플 2개: 분류값은 ETC·OTC로 통일
INSERT INTO drug (item_seq, name, maker, etc_otc, class_name, shape, color, form, material, synced_at) VALUES
('202600001', '타이레놀정500밀리그램', '한국얀센', 'OTC', '해열·진통·소염제', '장방형', '흰색', '정제', '아세트아미노펜', '2026-10-10 00:00:00'),
('202600004', '아모디핀정5밀리그램', '한미약품', 'ETC', '혈압강하제', '원형', '흰색', '정제', '암로디핀베실산염', '2026-10-10 00:00:00');

-- 3-7. 프로필 15개 각각에 내 약 2개씩, 총 30개
INSERT INTO my_drug (profile_id, item_seq, dose_per_time, timing, start_date, end_date, dose_times, source, registered_at, updated_at)
SELECT p.profile_id, d.item_seq, 1,
       CASE d.seq WHEN 1 THEN '식후 30분' ELSE '취침 전' END,
       '2026-10-01', '2026-12-31',
       CASE d.seq WHEN 1 THEN '08:00' ELSE '21:00' END,
       'SEARCH', '2026-10-10 09:00:00', '2026-10-10 09:00:00'
FROM profile p
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
       p.profile_birth_year, p.profile_gender, p.profile_pregnant
FROM profile p
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
SELECT 'member' AS table_name, COUNT(*) AS row_count FROM member
UNION ALL SELECT 'profile', COUNT(*) FROM profile
UNION ALL SELECT 'consent', COUNT(*) FROM consent
UNION ALL SELECT 'consent_history', COUNT(*) FROM consent_history
UNION ALL SELECT 'drug', COUNT(*) FROM drug
UNION ALL SELECT 'notification_setting', COUNT(*) FROM notification_setting
UNION ALL SELECT 'my_drug', COUNT(*) FROM my_drug
UNION ALL SELECT 'chat_session', COUNT(*) FROM chat_session
UNION ALL SELECT 'chat_message', COUNT(*) FROM chat_message
UNION ALL SELECT 'chat_summary', COUNT(*) FROM chat_summary
UNION ALL SELECT 'photo', COUNT(*) FROM photo
UNION ALL SELECT 'ocr_candidate', COUNT(*) FROM ocr_candidate
UNION ALL SELECT 'dur_warning', COUNT(*) FROM dur_warning
UNION ALL SELECT 'dur_interaction', COUNT(*) FROM dur_interaction;
