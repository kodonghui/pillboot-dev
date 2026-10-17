package org.semiproject.pillbadeumserver.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/* 설명. @Scheduled 로 정해진 시각에 실행하는 작업을 켠다.
 *  ----------------------------------------------------------------------------------
 *  이 기능을 쓰는 요구사항
 *  - NOTI-003  : 복약 시각이 된 일정을 찾아 알림 보내기 (예: 매분 실행)
 *  CHAT-010(100일 지난 대화방 삭제)은 보류 상태이므로 작업을 등록하지 않습니다.
 *  비동기 후처리는 수업처럼 Boot의 applicationTaskExecutor.execute()를 사용합니다.
 *  별도 실행기나 사용하지 않는 @Async 설정을 미리 추가하지 않습니다.
 *  ----------------------------------------------------------------------------------
 *  사용 예) @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")   // 매분 0초
 *  cron 에는 zone = "Asia/Seoul" 을 꼭 적는다. 서버 시간대와 상관없이 한국 시각 기준으로 돈다.
 * */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
