package com.example.sso.admin;

import com.example.sso.admin.internal.sessionpolicy.application.SessionPolicyAdminService;
import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicySpec;
import com.example.sso.authpolicy.internal.application.PolicyAdminService;
import com.example.sso.authpolicy.internal.application.PolicyView;
import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.networkzone.NetworkZoneService;
import com.example.sso.session.networkzone.NetworkZoneSpec;
import com.example.sso.session.policy.SessionPolicyRequest;
import com.example.sso.session.policy.SessionPolicyView;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the admin policy read-projection paths, which map the LAZY-collection policy entities to their
 * presentation views ({@link SessionPolicyView}, {@link PolicyView}). These run OUTSIDE any request/test
 * transaction, so if the adapter does not keep a session open the projection hits the detached entity and
 * fails with {@code LazyInitializationException} — the exact regression this test reproduces. The test
 * therefore must NOT be {@code @Transactional} (an ambient tx would mask the bug). Created policies are
 * assigned to random ids (so they match no real user and cannot disturb other flows) and are deleted after.
 */
class PolicyAdminViewIT extends AbstractIntegrationTest {

    @Autowired
    SessionPolicyAdminService sessionPolicyAdmin;
    @Autowired
    PolicyAdminService authPolicyAdmin;
    @Autowired
    NetworkZoneService networkZones;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void sessionPolicyListAndCreateProjectAssignmentsWithoutLazyInit() {
        String user = UUID.randomUUID().toString();
        String role = UUID.randomUUID().toString();
        String office = networkZones.create(new NetworkZoneSpec("IT-Office-" + suffix(), null, List.of("10.0.0.0/8"))).id();
        String all = networkZones.create(new NetworkZoneSpec("IT-All-" + suffix(), null, List.of("0.0.0.0/0"))).id();

        SessionPolicyView created = sessionPolicyAdmin.create(new SessionPolicyRequest(
                "IT-Session-" + suffix(), 50, true, 480, 30, 5, "TOTP,FIDO2", 2, "TOTP,FIDO2", true, 0, true, "Lax",
                5, null, List.of(user), List.of(role),
                List.of(new IpRuleSpec(office, "ALLOW", 0), new IpRuleSpec(all, "BLOCK", 1))));
        cleanups.add(() -> {
            sessionPolicyAdmin.delete(UUID.fromString(created.id()));
            networkZones.delete(UUID.fromString(office));
            networkZones.delete(UUID.fromString(all));
        });

        // create() projected the freshly-persisted entity...
        assertThat(created.assignedUserIds()).containsExactly(user);
        assertThat(created.assignedRoleIds()).containsExactly(role);
        assertThat(created.ipRules()).extracting(IpRuleSpec::zoneId).containsExactly(office, all);

        // ...and list() projects entities re-read from the DB with their assignment sets + IP rules still LAZY.
        SessionPolicyView listed = sessionPolicyAdmin.list(0, 100).items().stream()
                .filter(p -> p.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(listed.assignedUserIds()).containsExactly(user);
        assertThat(listed.assignedRoleIds()).containsExactly(role);
        // IP rules project in priority order off the detached read.
        assertThat(listed.ipRules()).extracting(IpRuleSpec::action).containsExactly("ALLOW", "BLOCK");

        // The seeded Default (and every other) policy must also project cleanly off the detached read.
        assertThatCode(() -> sessionPolicyAdmin.list(0, 100)).doesNotThrowAnyException();
    }

    @Test
    void authPolicyListAndCreateProjectStepsAndAssignmentsWithoutLazyInit() {
        String user = UUID.randomUUID().toString();
        String role = UUID.randomUUID().toString();

        PolicyView created = authPolicyAdmin.create(new AuthPolicySpec(
                "IT-Auth-" + suffix(), 50, true, true, false,
                List.of(Set.of(AuthFactor.PASSWORD), Set.of(AuthFactor.TOTP, AuthFactor.FIDO2)),
                Set.of(UUID.fromString(user)), Set.of(UUID.fromString(role)), 5));
        cleanups.add(() -> authPolicyAdmin.delete(UUID.fromString(created.id())));

        assertThat(created.steps()).hasSize(2);
        assertThat(created.assignedUserIds()).containsExactly(user);

        // list() re-reads the policy with steps/allowedFactors and both assignment sets LAZY.
        PolicyView listed = authPolicyAdmin.list(0, 100).items().stream()
                .filter(p -> p.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(listed.steps()).hasSize(2);
        assertThat(listed.steps().get(1)).containsExactlyInAnyOrder("TOTP", "FIDO2");
        assertThat(listed.assignedUserIds()).containsExactly(user);
        assertThat(listed.assignedRoleIds()).containsExactly(role);

        assertThatCode(() -> authPolicyAdmin.list(0, 100)).doesNotThrowAnyException();
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
