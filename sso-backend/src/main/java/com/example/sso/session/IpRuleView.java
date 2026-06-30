package com.example.sso.session;

/** Admin view of an IP access rule. */
public record IpRuleView(String id, String cidr, String action, String description,
                         boolean enabled, int priority) {

    public static IpRuleView of(IpRule r) {
        return new IpRuleView(r.getId().toString(), r.getCidr(), r.getAction().name(),
                r.getDescription(), r.isEnabled(), r.getPriority());
    }
}
