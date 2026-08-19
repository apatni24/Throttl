package com.throttl.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class RateLimiterServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterServiceApplication.class, args);
    }
}
