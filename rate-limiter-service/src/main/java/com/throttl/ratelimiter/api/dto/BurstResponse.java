package com.throttl.ratelimiter.api.dto;

public record BurstResponse(int sent, int allowed, int rejected, int overspend, String rejectedBy) {
}
