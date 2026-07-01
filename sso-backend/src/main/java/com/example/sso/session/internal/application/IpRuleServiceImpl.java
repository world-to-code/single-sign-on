package com.example.sso.session.internal.application;

import com.example.sso.session.IpRuleRequest;
import com.example.sso.session.IpRuleService;
import com.example.sso.session.IpRuleView;
import com.example.sso.session.internal.domain.IpRule;
import com.example.sso.session.internal.domain.IpRuleRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Default {@link IpRuleService}. The active rule set is compiled to {@link IpAddressMatcher}s and
 * cached so {@link #isAllowed} is cheap on every request.
 */
@Service
@RequiredArgsConstructor
public class IpRuleServiceImpl implements IpRuleService {
    private record Compiled(IpAddressMatcher matcher, IpRule.Action action) {
    }

    private final IpRuleRepository repository;
    private volatile List<Compiled> compiled = List.of();
    private volatile boolean hasAllow = false;

    @PostConstruct
    @Transactional(readOnly = true)
    public void refresh() {
        List<IpRule> rules = repository.findAllByOrderByPriorityAsc();
        this.compiled = rules.stream()
                .filter(IpRule::isEnabled)
                .map(r -> new Compiled(new IpAddressMatcher(r.getCidr()), r.getAction()))
                .toList();
        this.hasAllow = compiled.stream().anyMatch(c -> c.action() == IpRule.Action.ALLOW);
    }

    @Override
    public boolean isAllowed(String ip) {
        if (ip == null) {
            return true;
        }

        boolean matchedAllow = false;
        for (Compiled c : compiled) {
            boolean matches = safeMatches(c.matcher(), ip);
            if (matches && c.action() == IpRule.Action.BLOCK) {
                return false;
            }
            if (matches && c.action() == IpRule.Action.ALLOW) {
                matchedAllow = true;
            }
        }

        return !hasAllow || matchedAllow;
    }

    private boolean safeMatches(IpAddressMatcher matcher, String ip) {
        try {
            return matcher.matches(ip);
        } catch (IllegalArgumentException e) {
            return false; // address-family mismatch (e.g. IPv6 client vs IPv4 rule)
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<IpRuleView> list() {
        return repository.findAllByOrderByPriorityAsc().stream().map(IpRuleView::of).toList();
    }

    @Override
    @Transactional
    public IpRuleView create(IpRuleRequest request) {
        validateCidr(request.cidr());

        IpRule rule = new IpRule(request.cidr().trim(), IpRule.Action.valueOf(request.action()),
                request.description(), request.enabled(), request.priority());
        IpRuleView view = IpRuleView.of(repository.save(rule));
        refresh();

        return view;
    }

    @Override
    @Transactional
    public IpRuleView update(UUID id, IpRuleRequest request) {
        validateCidr(request.cidr());

        IpRule rule = repository.findById(id).orElseThrow(() -> new NotFoundException("IP rule not found"));
        rule.update(request.cidr().trim(), IpRule.Action.valueOf(request.action()),
                request.description(), request.enabled(), request.priority());
        IpRuleView view = IpRuleView.of(repository.save(rule));
        refresh();

        return view;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("IP rule not found");
        }

        repository.deleteById(id);
        refresh();
    }

    private void validateCidr(String cidr) {
        try {
            new IpAddressMatcher(cidr.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid CIDR: " + cidr);
        }
    }
}
