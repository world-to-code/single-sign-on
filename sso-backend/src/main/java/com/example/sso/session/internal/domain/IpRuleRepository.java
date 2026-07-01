package com.example.sso.session.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IpRuleRepository extends JpaRepository<IpRule, UUID> {
    List<IpRule> findAllByOrderByPriorityAsc();
}
