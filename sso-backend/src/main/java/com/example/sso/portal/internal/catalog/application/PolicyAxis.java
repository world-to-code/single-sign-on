package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import java.util.UUID;

/**
 * Which policy field (and its tie-break priority) a {@code policy_binding} write targets — the AUTH
 * ({@code auth_policy_id}/{@code priority}) or the SESSION ({@code session_policy_id}/{@code session_priority})
 * axis. A co-located binding on the OTHER axis of the same row is preserved untouched. Each constant knows how to
 * point a binding's own field at a policy, so the shared attribute-reconcile logic stays axis-agnostic.
 */
enum PolicyAxis {
    AUTH {
        @Override
        void assign(PolicyBinding binding, UUID policyId, int priority) {
            binding.assignAuthPolicy(policyId);
            binding.reprioritize(priority);
        }
    },
    SESSION {
        @Override
        void assign(PolicyBinding binding, UUID policyId, int priority) {
            binding.assignSessionPolicy(policyId);
            binding.reprioritizeSession(priority);
        }
    };

    /** Point the binding's field for THIS axis at {@code policyId} and stamp its tie-break priority. */
    abstract void assign(PolicyBinding binding, UUID policyId, int priority);
}
