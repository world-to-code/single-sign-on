import { apiGet, type Page } from "@/api";
import { usersByIds } from "@/groups";
import { listRoles } from "@/roles";

/**
 * There is no single "list all policy bindings" endpoint — a binding (app × subject → auth and/or session
 * policy) is written as a side effect across several admin surfaces (applications, auth policies, session
 * policies, portal settings). This module aggregates those sources into one flat, read-only list for the
 * unified Bindings overview, MERGED so a single app × subject appears once with both its auth and session
 * policy (never as two half-empty rows). Each policy links to the surface that edits it. A source the caller
 * cannot read (403) is skipped, not fatal.
 */

/** ALL = an app-wide binding (no specific subject); OTHER = a named subject whose type we don't recognize. */
export type BindingSubjectKind = "USER" | "GROUP" | "ROLE" | "ALL" | "OTHER";
type BindingAppType = "OIDC" | "SAML" | "PORTAL";

/** One policy on one axis, with a deep link to where THAT policy binding is edited. */
export interface BindingAxis {
  policyName: string;
  editTo: string;
}

export interface PolicyBindingRow {
  key: string;
  appType: BindingAppType;
  /** For OIDC/SAML the application name; for PORTAL the portal id ("admin" | "user"), localized by the page. */
  appName: string;
  subjectKind: BindingSubjectKind;
  /** Resolved user/role/group name; empty only for an all-subjects (ALL) binding. */
  subjectLabel: string;
  auth: BindingAxis | null;
  session: BindingAxis | null;
}

export interface BindingsResult {
  rows: PolicyBindingRow[];
  /** True when at least one source was unreadable (a missing permission or a transient error) but not all —
   * the list is shown but may be incomplete, so the page warns rather than pretending it is exhaustive. */
  partial: boolean;
}

interface Application {
  id: string;
  type: BindingAppType;
  name: string;
  system: boolean;
  requiredPolicyId: string | null;
  requiredPolicyName: string | null;
}
interface Assignment { id: string; subjectType: string; subjectName: string; requiredPolicyId: string | null; }
interface AuthPolicy { id: string; name: string; appliesToLogin: boolean; assignedUserIds: string[]; assignedRoleIds: string[]; }
interface SessionPolicy { id: string; name: string; assignedUserIds: string[]; assignedRoleIds: string[]; }
interface PortalSettings { sessionPolicyId: string | null; }

const APPLICATIONS = "/admin/applications";

const value = <T,>(r: PromiseSettledResult<T>): T | null => (r.status === "fulfilled" ? r.value : null);

function subjectKind(subjectType: string): BindingSubjectKind {
  const t = subjectType.toUpperCase();
  return t === "USER" || t === "GROUP" || t === "ROLE" ? t : "OTHER";
}

export async function loadPolicyBindings(): Promise<BindingsResult> {
  const [appsR, authR, sessionR, portalAdminR, portalUserR, rolesR] = await Promise.allSettled([
    apiGet<Page<Application>>("/api/admin/applications?size=100"),
    apiGet<Page<AuthPolicy>>("/api/admin/auth-policies?size=100"),
    apiGet<Page<SessionPolicy>>("/api/admin/session-policies?size=100"),
    apiGet<PortalSettings>("/api/admin/portal-settings"),
    apiGet<PortalSettings>("/api/admin/portal-settings/user"),
    listRoles(),
  ]);

  // If EVERY primary source failed, surface a failure + retry rather than a false "no bindings" empty state.
  const primaries = [appsR, authR, sessionR, portalAdminR, portalUserR];
  if (primaries.every((r) => r.status === "rejected")) {
    throw (primaries.find((r) => r.status === "rejected") as PromiseRejectedResult).reason;
  }

  const apps = value(appsR)?.items ?? [];
  const authPolicies = value(authR)?.items ?? [];
  const sessionPolicies = value(sessionR)?.items ?? [];
  const portalAdmin = value(portalAdminR);
  const portalUser = value(portalUserR);
  const roleName = new Map((value(rolesR) ?? []).map((r) => [r.id, r.name]));
  const authPolicyName = new Map(authPolicies.map((p) => [p.id, p.name]));
  const sessionPolicyName = new Map(sessionPolicies.map((p) => [p.id, p.name]));

  // Per-app subject bindings live behind a per-app call; fan out over non-system (non-portal) apps only.
  const assignmentsR = await Promise.allSettled(
    apps
      .filter((a) => !a.system)
      .map((a) =>
        apiGet<Assignment[]>(`/api/admin/applications/${a.type.toLowerCase()}/${a.id}/assignments`)
          .then((list) => ({ app: a, list })),
      ),
  );

  // Resolve every user id referenced across the policy-scope bindings in one batch.
  const userIds = new Set<string>();
  for (const p of authPolicies) if (p.appliesToLogin) p.assignedUserIds.forEach((id) => userIds.add(id));
  for (const p of sessionPolicies) p.assignedUserIds.forEach((id) => userIds.add(id));
  const resolvedUsers = await usersByIds([...userIds]).catch(() => []);
  const userLabel = new Map(resolvedUsers.map((u) => [u.id, u.label]));

  // Merge every source into ONE row per (app, subject), setting the auth and/or session axis — so a subject
  // that has both a login policy and a session policy on the user portal is a single row, not two.
  const byKey = new Map<string, PolicyBindingRow>();
  const upsert = (
    appType: BindingAppType, appId: string, appName: string, kind: BindingSubjectKind, subjectId: string,
    subjectLabel: string, axis: "auth" | "session", binding: BindingAxis,
  ) => {
    const key = `${appType}:${appId}:${kind}:${subjectId}`;
    let row = byKey.get(key);
    if (!row) {
      row = { key, appType, appName, subjectKind: kind, subjectLabel, auth: null, session: null };
      byKey.set(key, row);
    }
    row[axis] = binding;
  };

  // 1. App-wide (all-subjects) AUTH binding on each OIDC/SAML app.
  for (const app of apps) {
    if (app.requiredPolicyId && app.requiredPolicyName) {
      upsert(app.type, app.id, app.name, "ALL", "", "", "auth", { policyName: app.requiredPolicyName, editTo: APPLICATIONS });
    }
  }

  // 2. Per-subject AUTH bindings on each app.
  for (const r of assignmentsR) {
    if (r.status !== "fulfilled") continue;
    const { app, list } = r.value;
    for (const a of list) {
      if (!a.requiredPolicyId) continue; // an assignment without a per-subject policy is not a policy binding
      upsert(app.type, app.id, app.name, subjectKind(a.subjectType), a.subjectName, a.subjectName, "auth",
        { policyName: authPolicyName.get(a.requiredPolicyId) ?? a.requiredPolicyId, editTo: APPLICATIONS });
    }
  }

  // 3. Login-scope AUTH bindings (the user portal), from each policy that applies at login.
  for (const p of authPolicies) {
    if (!p.appliesToLogin) continue;
    const axis: BindingAxis = { policyName: p.name, editTo: `/admin/auth-policies/${p.id}` };
    if (p.assignedUserIds.length === 0 && p.assignedRoleIds.length === 0) {
      upsert("PORTAL", "user", "user", "ALL", "", "", "auth", axis);
    }
    for (const id of p.assignedUserIds) upsert("PORTAL", "user", "user", "USER", id, userLabel.get(id) ?? id, "auth", axis);
    for (const id of p.assignedRoleIds) upsert("PORTAL", "user", "user", "ROLE", id, roleName.get(id) ?? id, "auth", axis);
  }

  // 4. Session assignment bindings (the user portal), from each session policy's scope.
  for (const p of sessionPolicies) {
    const axis: BindingAxis = { policyName: p.name, editTo: `/admin/session-policy/${p.id}` };
    for (const id of p.assignedUserIds) upsert("PORTAL", "user", "user", "USER", id, userLabel.get(id) ?? id, "session", axis);
    for (const id of p.assignedRoleIds) upsert("PORTAL", "user", "user", "ROLE", id, roleName.get(id) ?? id, "session", axis);
  }

  // 5. Portal-wide SESSION bindings (the admin console and the user portal), edited from Applications.
  if (portalAdmin?.sessionPolicyId) {
    upsert("PORTAL", "admin", "admin", "ALL", "", "", "session",
      { policyName: sessionPolicyName.get(portalAdmin.sessionPolicyId) ?? portalAdmin.sessionPolicyId, editTo: APPLICATIONS });
  }
  if (portalUser?.sessionPolicyId) {
    upsert("PORTAL", "user", "user", "ALL", "", "", "session",
      { policyName: sessionPolicyName.get(portalUser.sessionPolicyId) ?? portalUser.sessionPolicyId, editTo: APPLICATIONS });
  }

  const partial =
    primaries.some((r) => r.status === "rejected") || assignmentsR.some((r) => r.status === "rejected");
  return { rows: [...byKey.values()], partial };
}
