package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.ops.ClusterOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/instances")
public class InstanceController {

    private final ClusterOpsService clusterOpsService;

    public InstanceController(ClusterOpsService clusterOpsService) {
        this.clusterOpsService = clusterOpsService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return clusterOpsService.instances();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return clusterOpsService.instance(id);
    }
}
