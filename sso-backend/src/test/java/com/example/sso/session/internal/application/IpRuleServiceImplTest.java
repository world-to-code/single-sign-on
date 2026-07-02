package com.example.sso.session.internal.application;

import com.example.sso.session.IpRuleRequest;
import com.example.sso.session.internal.domain.IpRule;
import com.example.sso.session.internal.domain.IpRuleRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link IpRuleServiceImpl}. The hot path is {@link IpRuleServiceImpl#isAllowed} run
 * against the compiled cache, so the tests seed the repository then call {@code refresh()} (the
 * {@code @PostConstruct} that compiles rules to matchers) to reproduce the runtime state. Adversarial
 * focus: block-list denies, an allow-list flips to default-deny, disabled rules are ignored, and an
 * address-family mismatch never leaks an unhandled exception. Write paths validate the CIDR and refresh.
 */
@ExtendWith(MockitoExtension.class)
class IpRuleServiceImplTest {

    @Mock
    private IpRuleRepository repository;

    @InjectMocks
    private IpRuleServiceImpl service;

    private IpRule rule(String cidr, IpRule.Action action, boolean enabled, int priority) {
        return new IpRule(cidr, action, "desc", enabled, priority);
    }

    private void compile(IpRule... rules) {
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(List.of(rules));
        service.refresh();
    }

    @Test
    void nullIpIsAllowed() {
        compile();

        assertThat(service.isAllowed(null)).isTrue();
    }

    @Test
    void withNoRulesEverythingIsAllowed() {
        compile();

        assertThat(service.isAllowed("203.0.113.5")).isTrue();
    }

    @Test
    void blockRuleDeniesMatchingIp() {
        compile(rule("10.0.0.0/8", IpRule.Action.BLOCK, true, 10));

        assertThat(service.isAllowed("10.1.2.3")).isFalse();
        assertThat(service.isAllowed("203.0.113.5")).isTrue(); // outside the blocked range
    }

    @Test
    void disabledRuleIsIgnored() {
        compile(rule("10.0.0.0/8", IpRule.Action.BLOCK, false, 10));

        assertThat(service.isAllowed("10.1.2.3")).isTrue();
    }

    @Test
    void anyAllowRuleFlipsToDefaultDeny() {
        compile(rule("192.168.0.0/16", IpRule.Action.ALLOW, true, 10));

        assertThat(service.isAllowed("192.168.1.1")).isTrue();  // on the allow-list
        assertThat(service.isAllowed("203.0.113.5")).isFalse(); // not on it → denied
    }

    @Test
    void blockWinsOverAllowWhenBothMatch() {
        compile(rule("10.0.0.0/8", IpRule.Action.BLOCK, true, 1),
                rule("10.0.0.0/24", IpRule.Action.ALLOW, true, 2));

        assertThat(service.isAllowed("10.0.0.5")).isFalse();
    }

    @Test
    void addressFamilyMismatchIsSwallowedNotThrown() {
        compile(rule("10.0.0.0/8", IpRule.Action.BLOCK, true, 10));

        // An IPv6 client against an IPv4 rule must not blow up; the rule simply does not match.
        assertThat(service.isAllowed("2001:db8::1")).isTrue();
    }

    @Test
    void createRejectsAnInvalidCidrWithoutSaving() {
        IpRuleRequest request = new IpRuleRequest("not-a-cidr", "BLOCK", "d", true, 10);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsAndRefreshesTheCache() {
        IpRuleRequest request = new IpRuleRequest(" 203.0.113.0/24 ", "ALLOW", "office", true, 5);
        IpRule persisted = mock(IpRule.class); // a saved entity carries a generated id for the view
        when(persisted.getId()).thenReturn(UUID.randomUUID());
        when(persisted.getAction()).thenReturn(IpRule.Action.ALLOW);
        when(repository.save(any())).thenReturn(persisted);
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(List.of());

        service.create(request);

        verify(repository).save(any(IpRule.class));
        verify(repository).findAllByOrderByPriorityAsc(); // refresh() re-read the rules
    }

    @Test
    void updateOfMissingRuleThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        IpRuleRequest request = new IpRuleRequest("203.0.113.0/24", "BLOCK", "d", true, 10);

        assertThatThrownBy(() -> service.update(id, request)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteOfMissingRuleThrowsNotFoundAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void deleteRemovesAndRefreshes() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(true);
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(List.of());

        service.delete(id);

        verify(repository).deleteById(id);
        verify(repository).findAllByOrderByPriorityAsc();
    }
}
