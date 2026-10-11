package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.api.dto.BurstRequest;
import com.throttl.ratelimiter.api.dto.BurstResponse;
import com.throttl.ratelimiter.api.dto.RateLimitCheckRequest;
import com.throttl.ratelimiter.engine.RateLimitDecision;
import com.throttl.ratelimiter.engine.RateLimitEngine;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/v1/playground")
public class PlaygroundController {

    private static final int MAX_BURST = 2000;

    private final RateLimitEngine engine;

    public PlaygroundController(RateLimitEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/burst")
    public BurstResponse burst(@RequestBody BurstRequest request) {
        int count = Math.min(Math.max(request.count(), 1), MAX_BURST);
        RateLimitCheckRequest context = request.context() == null
                ? new RateLimitCheckRequest(null, null, null, null)
                : request.context();

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        String[] rejectedBy = {null};

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                tasks.add(() -> {
                    RateLimitDecision decision = engine.check(context);
                    if (decision.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                        if (rejectedBy[0] == null) {
                            rejectedBy[0] = decision.rejectedBy();
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> future : executor.invokeAll(tasks)) {
                future.get();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Burst check failed", ex);
        }

        int overspend = Math.max(0, allowed.get() - count);
        return new BurstResponse(count, allowed.get(), rejected.get(), overspend, rejectedBy[0]);
    }
}
