package com.usedlog;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UsedLogApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsedLogApplication.class, args);
    }

    /** One clock for the whole app, so tests can reason about "now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
