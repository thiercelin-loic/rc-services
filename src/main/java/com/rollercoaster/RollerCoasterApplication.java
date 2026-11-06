package com.rollercoaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the RollerCoaster Analytics Platform Backend.
 * Engineered for scalable dual-engine analytics with Spring Boot 4.1.1,
 * Java 21 Virtual Threads, Embedded DuckDB JNI, MySQL 8.0, and Redis 7.2.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class RollerCoasterApplication {

    @org.springframework.context.annotation.Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static void main(String[] args) {
        SpringApplication.run(RollerCoasterApplication.class, args);
    }
}
