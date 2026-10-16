package org.semiproject.pillbadeumserver.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/* 설명. BaseTimeEntity 의 created_at, updated_at 을 자동으로 채우는 기능을 켠다.
 *  메인 클래스(PillbadeumServerApplication)에 붙이지 않고 따로 둔 이유 :
 *  @WebMvcTest 같은 슬라이스 테스트에서 JPA 관련 빈이 없어 실패하는 문제를 피하기 위해서다.
 * */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
