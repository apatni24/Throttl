package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.ops.SystemInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/system")
public class SystemController {

    private final SystemInfoService systemInfoService;
    private final com.throttl.ratelimiter.ops.ClusterOpsService clusterOpsService;

    public SystemController(
            SystemInfoService systemInfoService,
            com.throttl.ratelimiter.ops.ClusterOpsService clusterOpsService
    ) {
        this.systemInfoService = systemInfoService;
        this.clusterOpsService = clusterOpsService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return systemInfoService.health();
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return systemInfoService.info();
    }

    @GetMapping("/self")
    public Map<String, Object> self() {
        return clusterOpsService.selfSnapshot();
    }
}
