package com.example.sso.session;

import java.util.List;
import java.util.UUID;

/**
 * CRUD + evaluation of IP access rules. Semantics (evaluated by priority): a matching enabled BLOCK
 * rule denies; if any enabled ALLOW rule exists, the client must match one (allow-list mode);
 * otherwise the request is allowed. The implementation (with its compiled-matcher cache) stays
 * module-internal.
 */
public interface IpRuleService {

    /** True if the client IP is permitted by the current rule set. */
    boolean isAllowed(String ip);

    List<IpRuleView> list();

    IpRuleView create(IpRuleRequest request);

    IpRuleView update(UUID id, IpRuleRequest request);

    void delete(UUID id);
}
