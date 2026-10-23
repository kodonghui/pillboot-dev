-- 로컬 개발용 DB/계정 준비. 관리자 연결에서 실행하세요.
-- 기존 DB와 계정은 유지하며 이미 있는 계정의 비밀번호는 바꾸지 않습니다.
-- [테스트 초기화] 데이터를 버리고 다시 만들 때만 다음 DROP 한 줄의 주석을 해제함.
-- pilldb의 회원·프로필·등록 약·일정·채팅 등 모든 MySQL 데이터가 삭제됨.
-- 삭제 후 00 → 최신01 → 13(기본약관) → 02(샘플이 필요할 때만)를 실행하고 DROP을 다시 주석 처리함.
-- Qdrant 데이터와 업로드 파일은 이 MySQL 명령으로 삭제되지 않음.
-- DROP DATABASE IF EXISTS pilldb;

CREATE DATABASE IF NOT EXISTS pilldb
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'pill'@'%' IDENTIFIED BY 'pill';
GRANT ALL PRIVILEGES ON pilldb.* TO 'pill'@'%';
USE pilldb;
