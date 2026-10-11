package com.throttl.ratelimiter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
public class RedisIoConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisIoConfig.class);

    @Bean
    static BeanPostProcessor lettuceShareNativeConnection(RateLimiterProperties properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof LettuceConnectionFactory factory) {
                    boolean share = properties.redisShareNativeConnection();
                    factory.setShareNativeConnection(share);
                    log.info("check-io={} lettuce shareNativeConnection={}", properties.checkIo(), share);
                }
                return bean;
            }
        };
    }
}
