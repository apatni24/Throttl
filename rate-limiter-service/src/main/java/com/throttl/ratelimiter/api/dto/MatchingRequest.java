package com.throttl.ratelimiter.api.dto;

import com.throttl.ratelimiter.domain.Scope;

import java.util.Map;

public record MatchingRequest(Scope scope, Map<String, String> conditions) {
}
