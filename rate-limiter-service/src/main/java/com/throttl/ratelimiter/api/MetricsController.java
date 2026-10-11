package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.ops.ClusterOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/metrics")
public class MetricsController {

    private final ClusterOpsService clusterOpsService;

    public MetricsController(ClusterOpsService clusterOpsService) {
        this.clusterOpsService = clusterOpsService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return clusterOpsService.overview();
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@RequestParam(defaultValue = "1h") String range) {
        return clusterOpsService.analytics(range);
    }
}
