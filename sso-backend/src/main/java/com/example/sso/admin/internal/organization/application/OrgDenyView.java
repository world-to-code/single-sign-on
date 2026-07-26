package com.example.sso.admin.internal.organization.application;

import com.example.sso.user.deny.DenyRow;
import java.util.List;

/**
 * An organization's deny-management state for the console: the {@code candidates} an admin may withhold
 * org-wide (the tenant-grantable permission catalog — the permissions that can be held in the org) and the
 * denies already authored on the org, each liftable by id. The platform veto (org-null) is excluded.
 */
public record OrgDenyView(List<String> candidates, List<DenyRow> denies) {
}
