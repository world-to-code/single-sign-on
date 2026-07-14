import { apiGet, type Page } from "@/api";
import { usersByIds } from "@/groups";
import { listRoles } from "@/roles";

/**
 * There is no single "list all policy bindings" endpoint — a binding (app × subject → auth and/or session
 * policy) is written as a side effect across five admin surfaces. This module aggregates those sources into
 * one flat, read-only list for the unified Bindings overview. Each source is fetched independently and a
 * source the caller cannot read (403) is skipped, not fatal, so a tenant admin still sees what it can.
 */

/** ALL = an app-wide binding (no specific subject); OTHER = a named subject whose type we don't recognize. */
export type BindingSubjectKind = "USER" | "GROUP" | "ROLE" | "ALL" | "OTHER";
type BindingAppType = "OIDC" | "SAML" | "PORTAL";

export interface PolicyBindingRow {
  key: string;
  appType: BindingAppType;
  /** For OIDC/SAML the application name; for PORTAL the portal id ("admin" | "user"), localized by the page. */
  appName: string;
  subjectKind: BindingSubjectKind;
  /** Resolved user/role/group name; empty only for an all-subjects (ALL) binding. */
  subjectLabel: string;
  authPolicyName: string | null;
  sessionPolicyName: string | null;
  /** Route to the existing surface that edits this binding. */
  editTo: string;
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

const value = <T,>(r: PromiseSettledResult<T>): T | null => (r.status === "fulfilled" ? r.value : null);

// A per-app assignment always names a concrete subject, so an unrecognized type becomes OTHER (still shows the
// name), never ALL (which the page reads as "no subject" and would hide the real grantee).
function assignmentKind(subjectType: string): BindingSubjectKind {
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

  // A binding source the caller cannot read (403) or that transiently fails is skipped — but if EVERY primary
  // source failed, the page must show a failure + retry, not an empty "no bindings" (which would be a lie). So
  // re-throw the first reason only when nothing loaded; a partial failure is flagged instead.
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

  const rows: PolicyBindingRow[] = [];

  // 1. App-wide (all-subjects) AUTH binding, straight off the applications list.
  for (const app of apps) {
    if (app.requiredPolicyId) {
      rows.push({
        key: `appwide-${app.type}-${app.id}`, appType: app.type, appName: app.name,
        subjectKind: "ALL", subjectLabel: "",
        authPolicyName: app.requiredPolicyName, sessionPolicyName: null, editTo: "/admin/applications",
      });
    }
  }

  // 2. Per-subject AUTH bindings on each app.
  for (const r of assignmentsR) {
    if (r.status !== "fulfilled") continue;
    const { app, list } = r.value;
    for (const a of list) {
      if (!a.requiredPolicyId) continue; // an assignment without a per-subject policy is not a policy binding
      rows.push({
        key: `assign-${a.id}`, appType: app.type, appName: app.name,
        subjectKind: assignmentKind(a.subjectType), subjectLabel: a.subjectName,
        authPolicyName: authPolicyName.get(a.requiredPolicyId) ?? null, sessionPolicyName: null,
        editTo: "/admin/applications",
      });
    }
  }

  // 3. Login-scope AUTH bindings (the user portal), from each policy that applies at login.
  for (const p of authPolicies) {
    if (!p.appliesToLogin) continue;
    const editTo = `/admin/auth-policies/${p.id}`;
    if (p.assignedUserIds.length === 0 && p.assignedRoleIds.length === 0) {
      rows.push(portalRow(`authlogin-${p.id}-all`, "user", "ALL", "", p.name, null, editTo));
    }
    for (const id of p.assignedUserIds) {
      rows.push(portalRow(`authlogin-${p.id}-u-${id}`, "user", "USER", userLabel.get(id) ?? id, p.name, null, editTo));
    }
    for (const id of p.assignedRoleIds) {
      rows.push(portalRow(`authlogin-${p.id}-r-${id}`, "user", "ROLE", roleName.get(id) ?? id, p.name, null, editTo));
    }
  }

  // 4. Session assignment bindings (the user portal), from each session policy's scope.
  for (const p of sessionPolicies) {
    const editTo = `/admin/session-policy/${p.id}`;
    for (const id of p.assignedUserIds) {
      rows.push(portalRow(`session-${p.id}-u-${id}`, "user", "USER", userLabel.get(id) ?? id, null, p.name, editTo));
    }
    for (const id of p.assignedRoleIds) {
      rows.push(portalRow(`session-${p.id}-r-${id}`, "user", "ROLE", roleName.get(id) ?? id, null, p.name, editTo));
    }
  }

  // 5. Portal-wide SESSION bindings (the admin console and the user portal).
  if (portalAdmin?.sessionPolicyId) {
    rows.push(portalRow("portal-admin", "admin", "ALL", "", null,
      sessionPolicyName.get(portalAdmin.sessionPolicyId) ?? null, "/admin/applications"));
  }
  if (portalUser?.sessionPolicyId) {
    rows.push(portalRow("portal-user", "user", "ALL", "", null,
      sessionPolicyName.get(portalUser.sessionPolicyId) ?? null, "/admin/applications"));
  }

  const partial =
    primaries.some((r) => r.status === "rejected") || assignmentsR.some((r) => r.status === "rejected");
  return { rows, partial };
}

function portalRow(
  key: string, portalId: "admin" | "user", subjectKind: BindingSubjectKind, subjectLabel: string,
  authPolicyName: string | null, sessionPolicyName: string | null, editTo: string,
): PolicyBindingRow {
  return { key, appType: "PORTAL", appName: portalId, subjectKind, subjectLabel, authPolicyName, sessionPolicyName, editTo };
}
