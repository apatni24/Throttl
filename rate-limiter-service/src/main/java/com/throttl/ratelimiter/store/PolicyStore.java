package com.throttl.ratelimiter.store;

import com.throttl.ratelimiter.domain.Policy;
import com.throttl.ratelimiter.domain.PolicyValidationException;
import com.throttl.ratelimiter.domain.PolicyValidator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class PolicyStore {

    private final PolicyJpaRepository repository;

    public PolicyStore(PolicyJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Policy create(Policy policy) {
        PolicyValidator.validate(policy);
        if (repository.existsById(policy.id())) {
            throw new PolicyValidationException("Duplicate policy ID: " + policy.id());
        }
        return PolicyMapper.toDomain(repository.save(PolicyMapper.toNewEntity(policy)));
    }

    @Transactional(readOnly = true)
    public Optional<Policy> findById(String id) {
        return repository.findById(id).map(PolicyMapper::toDomain);
    }

    @Transactional(readOnly = true)
    public List<Policy> findAll() {
        return repository.findAll().stream().map(PolicyMapper::toDomain).toList();
    }

    @Transactional(readOnly = true)
    public List<Policy> findEnabled() {
        return repository.findAllByEnabledTrue().stream().map(PolicyMapper::toDomain).toList();
    }

    @Transactional
    public Policy update(String id, Policy policy) {
        PolicyValidator.validateId(id);
        PolicyValidator.validate(policy);
        if (!id.equals(policy.id())) {
            throw new PolicyValidationException("Path id and body id must match");
        }
        PolicyEntity entity = repository.findById(id)
                .orElseThrow(() -> new PolicyNotFoundException(id));
        PolicyMapper.apply(entity, policy);
        return PolicyMapper.toDomain(repository.save(entity));
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new PolicyNotFoundException(id);
        }
        repository.deleteById(id);
    }
}
