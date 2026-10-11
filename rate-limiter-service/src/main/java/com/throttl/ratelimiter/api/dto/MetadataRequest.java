package com.throttl.ratelimiter.api.dto;

import com.throttl.ratelimiter.domain.FailureMode;

public record MetadataRequest(Boolean enabled, Integer priority, FailureMode failureMode) {
}
