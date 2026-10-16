-- 로컬 개발용 DB/계정 준비. 관리자 연결에서 실행하세요.
-- 기존 DB와 계정은 유지하며 이미 있는 계정의 비밀번호는 바꾸지 않습니다.
CREATE DATABASE IF NOT EXISTS pilldb
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'pill'@'%' IDENTIFIED BY 'pill';
GRANT ALL PRIVILEGES ON pilldb.* TO 'pill'@'%';
USE pilldb;
