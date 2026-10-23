-- 가입에 필요한 기존 프로젝트 약관4종을 샘플 계정/약과 분리함.
-- 신규00→01→13, 기존 DB는 구조 갱신 뒤 실행함. 기존 약관·동의 이력은 수정하지 않음.
-- 같은 종류의 약관이 이미 있으면 그 내용·버전·활성 상태를 보존함.
INSERT INTO consent (terms_type,terms_version,terms_title,terms_content,is_required,is_active,effective_at,created_at)
SELECT seed.terms_type,'1.0',seed.terms_title,seed.terms_content,seed.is_required,TRUE,'2026-10-01 00:00:00',CURRENT_TIMESTAMP
FROM (
    SELECT 'PERSONAL_INFO' AS terms_type,'개인정보 수집 및 이용 동의' AS terms_title,
        '서비스 제공과 회원 관리에 필요한 개인정보를 수집 및 이용합니다.' AS terms_content,TRUE AS is_required
    UNION ALL SELECT 'SENSITIVE_INFO','민감정보 수집 및 이용 동의','건강 및 복약 관련 민감정보를 복약 관리와 상담 서비스에 이용합니다.',TRUE
    UNION ALL SELECT 'MARKETING','마케팅 정보 수신 동의','이벤트와 혜택 정보를 전송할 수 있습니다.',FALSE
    UNION ALL SELECT 'MEDICATION_REMINDER','복약 알림 수신 동의','등록된 복약 시간에 맞추어 알림을 발송합니다.',FALSE
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM consent existing WHERE existing.terms_type=seed.terms_type);

SELECT CASE WHEN COUNT(DISTINCT terms_type)=2 THEN 'READY' ELSE 'BLOCKED' END AS base_consent_status
FROM consent
WHERE terms_type IN ('PERSONAL_INFO','SENSITIVE_INFO') AND is_required=TRUE AND is_active=TRUE AND effective_at<=CURRENT_TIMESTAMP;
