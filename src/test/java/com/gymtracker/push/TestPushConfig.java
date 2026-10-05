package com.gymtracker.push;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestPushConfig {

    @Bean
    @Primary
    FakePushSender fakePushSender() {
        return new FakePushSender();
    }
}
