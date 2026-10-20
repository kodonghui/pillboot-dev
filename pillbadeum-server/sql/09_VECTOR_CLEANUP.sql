-- BUG-010/PROF-004: 삭제한 대화방의 벡터 정리를 재시도하기 위한 원문 없는 요청 목록임.
-- FK를 두지 않아 프로필/대화방 삭제 뒤에도 남으며 Qdrant 확인 완료 후에만 제거함.
CREATE TABLE IF NOT EXISTS vector_cleanup (
    session_id BIGINT NOT NULL PRIMARY KEY,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='vector_cleanup' AND ((column_name='session_id' AND data_type='bigint' AND column_key='PRI' AND is_nullable='NO') OR (column_name='requested_at' AND data_type='timestamp' AND is_nullable='NO')))=2,'READY','BLOCKED') AS vector_cleanup_status;
