package com.vandrae.patchnotes.fetch.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The poller reads the time from a {@link Clock} so tests can control it. */
@Configuration(proxyBeanMethods = false)
class FetchClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
