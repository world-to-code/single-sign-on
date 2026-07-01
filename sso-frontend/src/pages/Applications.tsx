import { useEffect, useState } from "react";
import { AppWindow, Lock, Network, Settings, Trash2, UserPlus, Users as UsersIcon } from "lucide-react";
import { apiGet, apiPost, apiPut } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

interface Application { id: string; type: "OIDC" | "SAML"; name: string; launchUrl: string | null; system: boolean; requiredPolicyId: string | null; requiredPolicyName: string | null; }
interface PortalSettings { reauthIntervalMinutes: number; elevationTokenTtlMinutes: number; sessionIdleTimeoutMinutes: number; sessionAbsoluteLifetimeMinutes: number; }

const settingFields: { key: keyof PortalSettings; label: string; hint: string }[] = [
  { key: "reauthIntervalMinutes", label: "Re-authentication interval (min)", hint: "How recent the deliberate step-up must be to use the admin API. Older ⇒ re-prompt for a strong factor." },
  { key: "elevationTokenTtlMinutes", label: "Elevation token lifetime (min)", hint: "TTL of the admin-console access token (the privilege-elevation proof)." },
  { key: "sessionIdleTimeoutMinutes", label: "Admin session idle timeout (min)", hint: "The admin session ends after this long with no admin activity." },
  { key: "sessionAbsoluteLifetimeMinutes", label: "Admin session absolute lifetime (min)", hint: "The admin session ends this long after it first elevated, regardless of activity." },
];
interface Assignment { id: string; subjectType: string; subjectName: string; requiredPolicyId: string | null; }
interface Role { id: string; name: string; }
interface User { id: string; username: string; }
interface Policy { id: string; name: string; appliesToLogin: boolean; }

export default function Applications() {
  const confirmDelete = useDeleteConfirm();
  const [apps, setApps] = useState<Application[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [policies, setPolicies] = useState<Policy[]>([]);

  const [active, setActive] = useState<Application | null>(null);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [subjectType, setSubjectType] = useState<"USER" | "ROLE">("ROLE");
  const [subjectId, setSubjectId] = useState("");
  const [requiredPolicyId, setRequiredPolicyId] = useState("");
  const [appPolicyId, setAppPolicyId] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  // Admin-portal security settings (only for the system app).
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState<PortalSettings | null>(null);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [settingsSaved, setSettingsSaved] = useState(false);

  function loadApps() {
    apiGet<Application[]>("/api/admin/applications")
      .then((list) => { setApps(list); setActive((cur) => (cur ? list.find((a) => a.type === cur.type && a.id === cur.id) ?? cur : cur)); })
      .catch((e) => setError(String(e)));
  }
  useEffect(() => {
    loadApps();
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
    apiGet<User[]>("/api/admin/users").then(setUsers).catch(() => undefined);
    apiGet<Policy[]>("/api/admin/auth-policies").then(setPolicies).catch(() => undefined);
  }, []);

  // Only app-only policies (Applies-to-login = off) are valid as per-app step-up policies.
  const appOnlyPolicies = policies.filter((p) => !p.appliesToLogin);
  const policyName = (id: string | null) => (id ? policies.find((p) => p.id === id)?.name ?? "policy" : null);

  function loadAssignments(app: Application) {
    apiGet<Assignment[]>(`/api/admin/applications/${app.type}/${app.id}/assignments`).then(setAssignments).catch(() => setAssignments([]));
  }
  function manage(app: Application) {
    setFormError(null); setActive(app); setSubjectType("ROLE"); setSubjectId(""); setRequiredPolicyId("");
    setAppPolicyId(app.requiredPolicyId ?? ""); setAssignments([]);
    loadAssignments(app);
  }

  function openSettings() {
    setSettingsError(null); setSettingsSaved(false); setSettings(null); setSettingsOpen(true);
    apiGet<PortalSettings>("/api/admin/portal-settings").then(setSettings).catch((e) => setSettingsError(String(e)));
  }

  async function saveSettings() {
    if (!settings) return;
    setSettingsError(null); setSettingsSaved(false);
    try {
      const saved = await apiPut<PortalSettings>("/api/admin/portal-settings", settings);
      setSettings(saved); setSettingsSaved(true);
    } catch (e) {
      setSettingsError(String(e));
    }
  }

  async function saveAppPolicy() {
    if (!active) return;
    setFormError(null);
    try {
      await apiPut(`/api/admin/applications/${active.type}/${active.id}/policy`, { requiredPolicyId: appPolicyId || null });
      loadApps();
    } catch (e) {
      setFormError(String(e));
    }
  }

  async function add() {
    if (!active || !subjectId) return;
    setFormError(null);
    try {
      await apiPost("/api/admin/applications/assignments", {
        appType: active.type, appId: active.id, subjectType, subjectId,
        requiredPolicyId: requiredPolicyId || null,
      });
      setSubjectId(""); setRequiredPolicyId("");
      loadAssignments(active);
    } catch (e) {
      setFormError(String(e));
    }
  }

  async function removeAssignment(a: Assignment) {
    await confirmDelete({
      title: "Remove access?",
      description: `${a.subjectName} will lose access to ${active?.name}.`,
      confirmText: "Remove",
      path: `/api/admin/applications/assignments/${a.id}`,
      onDeleted: () => { if (active) loadAssignments(active); },
    });
  }

  const options = subjectType === "ROLE"
    ? roles.map((r) => ({ id: r.id, label: r.name }))
    : users.map((u) => ({ id: u.id, label: u.username }));

  return (
    <>
      <PageHeader title="Applications" description="Assign OIDC and SAML applications to users and groups for the user portal." />

      <DataList
        data={apps}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<AppWindow className="size-8" />} title="No applications" hint="Register an OIDC client or SAML provider first." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow><TableHead>Application</TableHead><TableHead>Type</TableHead><TableHead>Launch URL</TableHead><TableHead className="w-0" /></TableRow>
            </TableHeader>
            <TableBody>
              {items.map((app) => (
                <TableRow key={`${app.type}:${app.id}`}>
                  <TableCell className="font-medium">
                    <span className="inline-flex flex-wrap items-center gap-2">
                      {app.type === "SAML" ? <Network className="size-4 text-muted-foreground" /> : <AppWindow className="size-4 text-muted-foreground" />}
                      {app.name}
                      {app.system && <Badge variant="secondary" title="Platform-managed: auto-granted to admins, cannot be edited or deleted"><Lock className="size-3" /> System</Badge>}
                      {app.requiredPolicyName && <Badge variant="default" title="Sign-on policy required for all users">🔒 {app.requiredPolicyName}</Badge>}
                    </span>
                  </TableCell>
                  <TableCell><Badge variant="muted">{app.type}</Badge></TableCell>
                  <TableCell className="max-w-xs truncate font-mono text-xs text-muted-foreground">{app.launchUrl ?? "—"}</TableCell>
                  <TableCell className="text-right">
                    {app.system
                      ? <Button variant="outline" size="sm" onClick={openSettings}><Settings /> Portal settings</Button>
                      : <Button variant="outline" size="sm" onClick={() => manage(app)}><UsersIcon /> Manage access</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      <Dialog open={!!active} onOpenChange={(o) => { if (!o) setActive(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Manage access — {active?.name}</DialogTitle>
            <DialogDescription>Users and groups assigned here can launch this app from their portal.</DialogDescription>
          </DialogHeader>

          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}

          <div className="space-y-2 rounded-md border bg-muted/30 p-3">
            <Label>Sign-on policy <span className="text-muted-foreground">(required for everyone accessing this app)</span></Label>
            <div className="flex gap-2">
              <Select value={appPolicyId} onChange={(e) => setAppPolicyId(e.target.value)} className="flex-1">
                <option value="">No app policy (base login only)</option>
                {appOnlyPolicies.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </Select>
              <Button variant="outline" onClick={saveAppPolicy}>Save</Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Every user opening this app must complete this policy's factors (step-up) — independent of the per-group/user
              assignments below. Only <strong>app-only</strong> policies appear (create one in Authentication Policies with
              “Use for login” <strong>off</strong>).
            </p>
          </div>

          <Label className="pt-1">Per-user / group access</Label>
          <div className="rounded-md border">
            {assignments.length === 0 ? (
              <p className="p-4 text-sm text-muted-foreground">No assignments yet.</p>
            ) : assignments.map((a) => (
              <div key={a.id} className="flex items-center justify-between border-b p-3 last:border-0">
                <span className="flex flex-wrap items-center gap-2 text-sm">
                  <Badge variant={a.subjectType === "ROLE" ? "secondary" : "outline"}>{a.subjectType === "ROLE" ? "Group" : "User"}</Badge>
                  {a.subjectName}
                  {a.requiredPolicyId && <Badge variant="default">+ {policyName(a.requiredPolicyId)}</Badge>}
                </span>
                <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => removeAssignment(a)}><Trash2 /></Button>
              </div>
            ))}
          </div>

          <div className="space-y-2">
            <Label>Assign to</Label>
            <div className="flex gap-2">
              <Select value={subjectType} onChange={(e) => { setSubjectType(e.target.value as "USER" | "ROLE"); setSubjectId(""); }} className="w-32">
                <option value="ROLE">Group</option><option value="USER">User</option>
              </Select>
              <Select value={subjectId} onChange={(e) => setSubjectId(e.target.value)} className="flex-1">
                <option value="">Select…</option>
                {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
              </Select>
            </div>
            <Label>Extra authentication <span className="text-muted-foreground">(optional)</span></Label>
            <div className="flex gap-2">
              <Select value={requiredPolicyId} onChange={(e) => setRequiredPolicyId(e.target.value)} className="flex-1">
                <option value="">No extra authentication</option>
                {appOnlyPolicies.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </Select>
              <Button onClick={add} disabled={!subjectId}><UserPlus /> Add</Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Selecting a policy requires the assigned users to complete its factors (e.g. an extra passkey/TOTP) when launching this app.
            </p>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={settingsOpen} onOpenChange={setSettingsOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2"><Lock className="size-4" /> Admin Portal security</DialogTitle>
            <DialogDescription>
              Session and re-authentication rules that apply <strong>only</strong> to the admin portal — separate from
              per-user session policies. Changes take effect on the next admin request.
            </DialogDescription>
          </DialogHeader>

          {settingsError && <Alert variant="destructive"><AlertDescription>{settingsError}</AlertDescription></Alert>}
          {settingsSaved && <Alert variant="success"><AlertDescription>Saved.</AlertDescription></Alert>}

          {settings ? (
            <div className="space-y-4">
              {settingFields.map((f) => (
                <div key={f.key} className="space-y-1.5">
                  <Label htmlFor={f.key}>{f.label}</Label>
                  <Input id={f.key} type="number" min={1} value={settings[f.key]}
                         onChange={(e) => setSettings((s) => (s ? { ...s, [f.key]: Number(e.target.value) } : s))} />
                  <p className="text-xs text-muted-foreground">{f.hint}</p>
                </div>
              ))}
            </div>
          ) : !settingsError ? <p className="text-sm text-muted-foreground">Loading…</p> : null}

          <DialogFooter>
            <Button variant="outline" onClick={() => setSettingsOpen(false)}>Close</Button>
            <Button onClick={saveSettings} disabled={!settings}>Save changes</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
