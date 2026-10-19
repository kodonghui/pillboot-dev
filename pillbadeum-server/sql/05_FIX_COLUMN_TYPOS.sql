-- 기존 DB의 컬럼명 오타 수정입니다. 04 다음에 대상 DB를 선택하고 한 번만 실행하세요.
-- 신규 DB는 변경된 01번에 바른 이름이 들어 있으므로 이 파일을 추가 실행하지 않습니다.
-- RENAME COLUMN은 자료형·NULL·기본값·주석·제약과 저장된 값을 그대로 두고 이름만 바꿉니다.
-- 이 파일을 적용하기 전 서버는 예전 이름을 찾으므로, 적용 후 최신 코드로 서버를 다시 시작하세요.

SELECT DATABASE() AS target_database;

ALTER TABLE `profile` RENAME COLUMN `realationship_type` TO `relationship_type`;
ALTER TABLE `drug` RENAME COLUMN `matrial` TO `material`;
