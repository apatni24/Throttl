package com.throttl.ratelimiter.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyJpaRepository extends JpaRepository<PolicyEntity, String> {

    List<PolicyEntity> findAllByEnabledTrue();
}
