package com.throttl.ratelimiter.domain;

import java.util.Map;

public record Matching(Scope scope, Map<String, String> conditions) {
}
