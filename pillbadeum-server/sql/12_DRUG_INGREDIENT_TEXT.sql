-- CHK-17: 100자를 넘는 공식 복합 성분도 전체 보존함. 기존 값과 NULL 불가 계약을 유지함.
-- 기존 DB에서 11 적용 뒤 실행함. 최신01로 만든 DB에는 이미 같은 구조가 있음.
ALTER TABLE drug MODIFY COLUMN material TEXT NOT NULL COMMENT '공식 성분명 전체';

SELECT CASE WHEN DATA_TYPE='text' AND IS_NULLABLE='NO' THEN 'READY' ELSE 'BLOCKED' END AS ingredient_text_status
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='drug' AND column_name='material';
