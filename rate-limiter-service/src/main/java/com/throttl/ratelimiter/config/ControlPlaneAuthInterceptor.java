package com.throttl.ratelimiter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.throttl.ratelimiter.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Requires {@code Authorization: Bearer <admin token>} on every state-changing
 * control-plane call. Reads and the data-plane {@code POST /v1/check} stay open.
 */
public class ControlPlaneAuthInterceptor implements HandlerInterceptor {

    private static final Set<String> READ_METHODS = Set.of(
            HttpMethod.GET.name(),
            HttpMethod.HEAD.name(),
            HttpMethod.OPTIONS.name()
    );
    private static final String BEARER = "Bearer ";

    private final RateLimiterProperties properties;
    private final ObjectMapper objectMapper;

    public ControlPlaneAuthInterceptor(RateLimiterProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (READ_METHODS.contains(request.getMethod())) {
            return true;
        }
        String expected = properties.adminToken();
        if (expected.isEmpty()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "admin_token_not_configured",
                    "Control-plane writes are disabled until THROTTL_ADMIN_TOKEN is set");
            return false;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Admin bearer token required");
            return false;
        }
        if (!constantTimeEquals(header.substring(BEARER.length()).trim(), expected)) {
            reject(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid admin token");
            return false;
        }
        return true;
    }

    private void reject(HttpServletResponse response, HttpStatus status, String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(error, message));
    }

    private static boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(sha256(provided), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
