package org.semiproject.pillbadeumserver.global.config;

import java.time.Clock;
import org.semiproject.pillbadeumserver.common.config.TimeZoneConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {
    @Bean Clock clock() { return Clock.system(TimeZoneConfig.ZONE); }
}
