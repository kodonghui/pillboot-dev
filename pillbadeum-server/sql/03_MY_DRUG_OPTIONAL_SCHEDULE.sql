-- 기존 14개 테이블 스키마의 my_drug 일정 삭제 지원용 수동 변경입니다.
-- 실행하지 않은 변경안입니다. 백업·대상 DB 확인 후 명시적으로 선택한 DB에서 한 번만 실행하세요.
-- 신규 DB는 변경된 01번을 사용하므로 이 파일을 추가 실행하지 않습니다.
-- 기존 약·일정 값은 변경하지 않으며, 다른 테이블이나 컬럼은 바꾸지 않습니다.
-- 기본 복용 시각은 MyDrug.create에서 채웁니다. 일정 없는 약이 자동 시각을 갖지 않도록 DB 기본값은 NULL입니다.

SELECT DATABASE() AS target_database;

ALTER TABLE `my_drug`
    MODIFY COLUMN `start_date` DATE NULL DEFAULT NULL COMMENT '복용 시작 날짜, 일정 없으면 NULL',
    MODIFY COLUMN `end_date` DATE NULL DEFAULT NULL COMMENT '복용 종료 날짜, 일정 등록 시 필수',
    MODIFY COLUMN `dose_times` VARCHAR(50) NULL DEFAULT NULL COMMENT '하루 1~3개 복용 시각, 일정 없으면 NULL',
    ADD CONSTRAINT chk_my_drug_schedule_presence CHECK (
        (`start_date` IS NULL AND `end_date` IS NULL AND `dose_times` IS NULL)
        OR (`start_date` IS NOT NULL AND `end_date` IS NOT NULL AND `dose_times` IS NOT NULL)
    );
