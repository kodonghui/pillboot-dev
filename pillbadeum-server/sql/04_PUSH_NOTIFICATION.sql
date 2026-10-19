-- 기존 DB의 복약 알림(웹 푸시) 지원용 수동 변경입니다. 03 다음에 대상 DB를 선택하고 한 번만 실행하세요.
-- 신규 DB는 변경된 01번에 같은 내용이 있으므로 이 파일을 추가 실행하지 않습니다.
-- 기존 행은 바꾸지 않습니다. 첫 SELECT 결과가 비어 있지 않으면 같은 회원의 알림 설정이 여러 행이므로
-- 남길 행을 팀이 정해 정리한 뒤 실행하세요. 그대로 실행하면 UNIQUE 추가가 실패합니다.

SELECT DATABASE() AS target_database;

SELECT member_id, COUNT(*) AS setting_rows
FROM notification_setting GROUP BY member_id HAVING COUNT(*) > 1;

ALTER TABLE `notification_setting`
    ADD CONSTRAINT uk_notification_setting_member UNIQUE (`member_id`);

CREATE TABLE IF NOT EXISTS `push_subscription` (
	`subscription_id`	BIGINT AUTO_INCREMENT	NOT NULL	COMMENT '브라우저 푸시 구독 코드',
	`member_id`	BIGINT	NOT NULL	COMMENT '회원 코드, 이 브라우저로 마지막에 알림을 켠 회원',
	`endpoint`	VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT '브라우저 푸시 주소(UK), 대소문자 구분',
	`p256dh`	VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT '브라우저 공개 키(base64url)',
	`auth`	VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin	NOT NULL	COMMENT '브라우저 인증 비밀값(base64url)',
	`user_agent`	VARCHAR(500)	NULL	COMMENT '브라우저 정보',
	`created_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '구독 최초 저장 시각',
	`updated_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '구독 정보 마지막 갱신 시각',
	PRIMARY KEY (`subscription_id`),
	CONSTRAINT uk_push_subscription_endpoint UNIQUE (`endpoint`),
	FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `notification_dispatch` (
	`dose_at`	DATETIME	NOT NULL	COMMENT '발송을 맡은 복용 시각(한국 시간, 분 단위). 같은 분은 한 번만 보냄',
	`claimed_at`	DATETIME	NOT NULL	DEFAULT CURRENT_TIMESTAMP	COMMENT '실행이 이 시각을 가져간 시각',
	PRIMARY KEY (`dose_at`)
) ENGINE=InnoDB;
