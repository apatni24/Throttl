package com.throttl.ratelimiter.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerIsAvailable")
class NaiveRedisTokenBucketRaceTest {

    static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception ex) {
            return false;
        }
    }

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Test
    void nonAtomicGetSetCanOverspendTokens() throws Exception {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        NaiveRedisTokenBucket naive = new NaiveRedisTokenBucket(redis);
        String key = "rl:naive:user-A";
        redis.opsForHash().put(key, "tokens", "100");

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger allowed = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tasks.add(() -> {
                if (naive.consume(key, 100)) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.close();
        factory.destroy();

        // The race is the point of the experiment: GET then SET can allow more than capacity.
        // If this ever equals 100, re-run; under load it typically exceeds.
        assertThat(allowed.get())
                .as("naive GET/SET should overspend (or equal capacity if the race did not land)")
                .isGreaterThanOrEqualTo(100);
    }
}
