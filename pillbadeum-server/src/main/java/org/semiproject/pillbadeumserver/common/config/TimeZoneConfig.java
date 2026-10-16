package org.semiproject.pillbadeumserver.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/* 설명. 서버의 기본 시간대를 한국(Asia/Seoul)으로 고정한다.
 *  복용 시각, 알림 발송 시각, 날짜별 복약 기록처럼 '몇 시'가 중요한 기능이 많다.
 *  개발자 PC, 배포 서버(대부분 UTC)의 시간대가 달라도 LocalDateTime.now() 가 같은 값을 내도록 맞춘다.
 *  시간대가 필요하면 TimeZoneConfig.ZONE 을 쓴다. 예) LocalDate.now(TimeZoneConfig.ZONE)
 * */
@Configuration
public class TimeZoneConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @PostConstruct
    public void setDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }
}
