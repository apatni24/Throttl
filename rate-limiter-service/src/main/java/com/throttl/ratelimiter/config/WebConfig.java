package com.throttl.ratelimiter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiterProperties properties;
    private final ObjectMapper objectMapper;

    public WebConfig(RateLimiterProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ControlPlaneAuthInterceptor(properties, objectMapper))
                .addPathPatterns("/v1/**")
                .excludePathPatterns("/v1/check");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.corsOrigins().split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        registry.addMapping("/v1/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
