-- 선택 실행: 테이블만 만든 비어 있는 개발 DB에 한 번만 실행하세요.
-- 실제 사용자 DB에는 실행하지 않습니다. 로그인 API는 아직 구현되지 않았습니다.
-- 약 item_seq와 약 이름/제조사의 조합은 공식 검증되지 않은 화면/DB용 fixture입니다.
-- 실제 공공데이터 호출 테스트에는 공식 응답으로 검증한 코드로 별도 준비하세요.
-- photo/ocr_candidate/DUR/push_subscription은 실제 처리 시 생성하므로 초기에는 비웁니다.

START TRANSACTION;

-- 3-1. 회원 5명 (원본 BCrypt 해시 예시 유지; 평문 비밀번호 검증 전 로그인 fixture로 사용 금지)
INSERT INTO member (email, password_hash, nickname, user_role, created_at) VALUES
('minji@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '김민지', 'ROLE_USER', '2026-10-01 09:00:00'),
('junho@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '이준호', 'ROLE_USER', '2026-10-02 10:00:00'),
('seoyeon@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '박서연', 'ROLE_USER', '2026-10-03 11:00:00'),
('hyunwoo@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '최현우', 'ROLE_USER', '2026-10-04 12:00:00'),
('admin@pillbadeum.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '필바다움관리자', 'ROLE_ADMIN', '2026-10-05 13:00:00');

-- 3-2. 약관 4종: 개인정보/민감정보/마케팅/복약 알림 수신 동의
INSERT INTO terms (terms_type, terms_version, terms_title, terms_content, is_required, is_active, effective_at) VALUES
('PERSONAL_INFO', '1.0', '개인정보 수집 및 이용 동의', '서비스 제공과 회원 관리에 필요한 개인정보를 수집 및 이용합니다.', TRUE, TRUE, '2026-10-01 00:00:00'),
('SENSITIVE_INFO', '1.0', '민감정보 수집 및 이용 동의', '건강 및 복약 관련 민감정보를 복약 관리와 상담 서비스에 이용합니다.', TRUE, TRUE, '2026-10-01 00:00:00'),
('MARKETING', '1.0', '마케팅 정보 수신 동의', '이벤트와 혜택 정보를 전송할 수 있습니다.', FALSE, TRUE, '2026-10-01 00:00:00'),
('MEDICATION_REMINDER', '1.0', '복약 알림 수신 동의', '등록된 복약 시간에 맞추어 알림을 발송합니다.', FALSE, TRUE, '2026-10-01 00:00:00');

-- 3-3. 회원당 본인·가족 프로필 3개, 총 15개
INSERT INTO profile (member_id, profile_name, relationship_type, profile_birth_year, profile_gender, profile_height, profile_weight, profile_pregnant) VALUES
(1, '민지', 'SELF', 1995, 'FEMALE', 163, 52, FALSE), (1, '엄마', 'MOTHER', 1968, 'FEMALE', 158, 57, FALSE), (1, '아들 도윤', 'CHILD', 2020, 'MALE', 105, 17, FALSE),
(2, '준호', 'SELF', 1990, 'MALE', 178, 74, FALSE), (2, '아버지', 'FATHER', 1962, 'MALE', 171, 70, FALSE), (2, '딸 지아', 'CHILD', 2018, 'FEMALE', 116, 21, FALSE),
(3, '서연', 'SELF', 1993, 'FEMALE', 167, 55, FALSE), (3, '할머니', 'GRANDMOTHER', 1945, 'FEMALE', 150, 48, FALSE), (3, '남편', 'SPOUSE', 1991, 'MALE', 180, 78, FALSE),
(4, '현우', 'SELF', 1988, 'MALE', 175, 69, FALSE), (4, '어머니', 'MOTHER', 1965, 'FEMALE', 155, 54, FALSE), (4, '아들 민석', 'CHILD', 2016, 'MALE', 124, 25, FALSE),
(5, '관리자', 'SELF', 1985, 'UNKNOWN', NULL, NULL, FALSE), (5, '부모님', 'PARENT', 1958, 'UNKNOWN', NULL, NULL, FALSE), (5, '테스트 자녀', 'CHILD', 2015, 'UNKNOWN', NULL, NULL, FALSE);

-- 서버의 8색 순서와 같은 예시 색상(회원별 기존 프로필 순서).
UPDATE profile SET color = CASE MOD(profile_id - 1, 3)
    WHEN 0 THEN '#E03131' WHEN 1 THEN '#F76707' ELSE '#F59F00' END;

-- 3-4. 약관 동의 이력 3건
INSERT INTO consent_history (member_id, terms_id, consent_status, recorded_at) VALUES
(1, 1, 'AGREED', '2026-10-01 09:02:00'),
(1, 2, 'AGREED', '2026-10-01 09:02:10'),
(2, 3, 'WITHDRAWN', '2026-10-08 18:30:00');

-- 3-5. 알림 설정: 회원 1은 켬, 회원 2는 끔
INSERT INTO notification_setting (member_id, push_enabled, updated_at) VALUES
(1, TRUE, '2026-10-10 08:00:00'),
(2, FALSE, '2026-10-10 08:05:00');

-- 3-6. 내 약 목록에서 참조할 의약품 9개
INSERT INTO drug (item_seq, name, maker, etc_otc, class_name, shape, color, form, material, synced_at) VALUES
('202600001', '타이레놀정500밀리그램', '한국얀센', 'OTC', '해열·진통·소염제', '장방형', '흰색', '정제', '아세트아미노펜', '2026-10-10 00:00:00'),
('202600002', '게보린정', '삼진제약', 'OTC', '해열·진통·소염제', '원형', '갈색', '정제', '아세트아미노펜·이소프로필안티피린', '2026-10-10 00:00:00'),
('202600003', '판콜에스내복액', '동화약품', 'OTC', '감기약', '액상', '갈색', '내복액', '아세트아미노펜·클로르페니라민', '2026-10-10 00:00:00'),
('202600004', '아모디핀정5밀리그램', '한미약품', 'ETC', '혈압강하제', '원형', '흰색', '정제', '암로디핀베실산염', '2026-10-10 00:00:00'),
('202600005', '크레스토정10밀리그램', '아스트라제네카', 'ETC', '고지혈증 치료제', '원형', '분홍색', '정제', '로수바스타틴칼슘', '2026-10-10 00:00:00'),
('202600006', '가스모틴정5밀리그램', '대웅제약', 'ETC', '위장운동조절제', '원형', '흰색', '정제', '모사프리드시트르산염', '2026-10-10 00:00:00'),
('202600007', '알레그라정120밀리그램', '사노피', 'ETC', '항히스타민제', '장방형', '분홍색', '정제', '펙소페나딘염산염', '2026-10-10 00:00:00'),
('202600008', '비오플250산', '건일제약', 'OTC', '정장제', '분말', '흰색', '산제', '사카로마이세스보울라디', '2026-10-10 00:00:00'),
('202600009', '오메가3연질캡슐1000밀리그램', '종근당건강', 'OTC', '영양보급제', '타원형', '투명 황색', '연질캡슐', '오메가3산', '2026-10-10 00:00:00');

-- 3-7. 프로필 15개 각각에 내 약 3개씩, 총 45개
INSERT INTO my_drug (profile_id, item_seq, dose_per_time, timing, start_date, end_date, dose_times, source)
SELECT p.profile_id, d.item_seq, 1,
       CASE d.seq WHEN 1 THEN '식후 30분' WHEN 2 THEN '취침 전' ELSE '필요 시' END,
       '2026-10-01', '2026-12-31',
       CASE d.seq WHEN 1 THEN '08:00' WHEN 2 THEN '21:00' ELSE '08:00,13:00,19:00' END,
       CASE d.seq WHEN 1 THEN 'SEARCH' WHEN 2 THEN 'PRESCRIPTION_PHOTO' ELSE 'PILL_PHOTO' END
FROM profile p
CROSS JOIN (
    SELECT 1 AS seq, '202600001' AS item_seq UNION ALL
    SELECT 2, '202600004' UNION ALL
    SELECT 3, '202600009'
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
INSERT INTO chat_message (session_id, turn_no, role, content, token_count, created_at)
SELECT cs.session_id, m.turn_no, m.role,
       CASE m.message_order
           WHEN 1 THEN CONCAT(cs.title, '에 대해 알려주세요.')
           WHEN 2 THEN '등록된 약과 프로필 정보를 기준으로 일반적인 복약 안내를 드립니다. 처방 변경은 의료진과 상의하세요.'
           WHEN 3 THEN '복용할 때 주의할 점도 알려주세요.'
           ELSE '이상 반응이 있거나 증상이 지속되면 복용을 중단하고 의사 또는 약사에게 상담하세요.'
       END,
       CASE WHEN m.role = 'USER' THEN 18 ELSE 42 END,
       DATE_ADD(cs.created_at, INTERVAL m.message_order MINUTE)
FROM chat_session cs
CROSS JOIN (
    SELECT 1 AS message_order, 1 AS turn_no, 'USER' AS role UNION ALL
    SELECT 2, 1, 'ASSISTANT' UNION ALL
    SELECT 3, 2, 'USER' UNION ALL
    SELECT 4, 2, 'ASSISTANT'
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
UNION ALL SELECT 'terms', COUNT(*) FROM terms
UNION ALL SELECT 'consent_history', COUNT(*) FROM consent_history
UNION ALL SELECT 'my_drug', COUNT(*) FROM my_drug
UNION ALL SELECT 'chat_session', COUNT(*) FROM chat_session
UNION ALL SELECT 'chat_message', COUNT(*) FROM chat_message
UNION ALL SELECT 'chat_summary', COUNT(*) FROM chat_summary;
