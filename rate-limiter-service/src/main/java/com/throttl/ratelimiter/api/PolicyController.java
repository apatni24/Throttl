package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.api.dto.PolicyRequest;
import com.throttl.ratelimiter.api.dto.PolicyResponse;
import com.throttl.ratelimiter.engine.PolicyCatalog;
import com.throttl.ratelimiter.engine.RuntimeBucketStore;
import com.throttl.ratelimiter.store.PolicyNotFoundException;
import com.throttl.ratelimiter.store.PolicyStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/policies")
public class PolicyController {

    private final PolicyStore policyStore;
    private final PolicyCatalog policyCatalog;
    private final RuntimeBucketStore runtimeBucketStore;

    public PolicyController(
            PolicyStore policyStore,
            PolicyCatalog policyCatalog,
            RuntimeBucketStore runtimeBucketStore
    ) {
        this.policyStore = policyStore;
        this.policyCatalog = policyCatalog;
        this.runtimeBucketStore = runtimeBucketStore;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse create(@RequestBody PolicyRequest request) {
        PolicyResponse response = PolicyResponse.from(policyStore.create(PolicyAssembler.fromRequest(request)));
        policyCatalog.invalidate();
        return response;
    }

    @GetMapping
    public List<PolicyResponse> list() {
        return policyStore.findAll().stream().map(PolicyResponse::from).toList();
    }

    @GetMapping("/{id}")
    public PolicyResponse get(@PathVariable String id) {
        return policyStore.findById(id)
                .map(PolicyResponse::from)
                .orElseThrow(() -> new PolicyNotFoundException(id));
    }

    @PutMapping("/{id}")
    public PolicyResponse update(@PathVariable String id, @RequestBody PolicyRequest request) {
        PolicyResponse response = PolicyResponse.from(policyStore.update(id, PolicyAssembler.fromRequest(request)));
        runtimeBucketStore.deleteBucketsForPolicy(id);
        policyCatalog.invalidate();
        return response;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        policyStore.delete(id);
        runtimeBucketStore.deleteBucketsForPolicy(id);
        policyCatalog.invalidate();
    }
}
