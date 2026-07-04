import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ChevronDown, ChevronUp, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut, errorMessage, type Page } from "../api";
import { listZones, searchZones } from "@/zones";
import { EditorPage } from "@/components/EditorPage";
import { InfoHint } from "@/components/InfoHint";
import { SearchSelect } from "@/components/SearchSelect";
import { SettingsSection } from "@/components/SettingsSection";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { tokens } from "@/lib/utils";
import { Field, Toggle } from "@/components/form/fields";
import { CheckboxGroup } from "@/components/form/CheckboxGroup";
import { UserMultiSelect } from "@/components/UserMultiSelect";

interface IpRuleWire { zoneId: string; action: string; priority: number }
interface SessionPolicy {
  id: string;
  name: string;
  priority: number;
  enabled: boolean;
  absoluteTimeoutMinutes: number;
  idleTimeoutMinutes: number;
  reauthIntervalMinutes: number;
  reauthFactors: string;
  sensitiveReauthWindowMinutes: number;
  stepUpFactors: string;
  bindClient: boolean;
  maxConcurrentSessions: number;
  rotateOnReauth: boolean;
  cookieSameSite: string;
  assignedUserIds: string[];
  assignedRoleIds: string[];
  ipRules: IpRuleWire[];
}
interface Role { id: string; name: string }
interface ZoneRule { zoneId: string; action: string } // order = priority

interface Editor {
  id: string | null;
  name: string;
  priority: string;
  enabled: boolean;
  absoluteTimeoutMinutes: string;
  idleTimeoutMinutes: string;
  reauthIntervalMinutes: string;
  reauthFactors: string;
  sensitiveReauthWindowMinutes: string;
  stepUpFactors: string;
  bindClient: boolean;
  maxConcurrentSessions: string;
  rotateOnReauth: boolean;
  cookieSameSite: string;
  roleIds: string[];
  userIds: string[];
  ipRules: ZoneRule[];
}

const REAUTH_FACTORS = ["TOTP", "FIDO2", "PASSWORD", "EMAIL"];
type Tab = "general" | "reauth" | "network" | "assign";
const TABS: { key: Tab; label: string }[] = [
  { key: "general", label: "General" },
  { key: "reauth", label: "Re-authentication" },
  { key: "network", label: "Network" },
  { key: "assign", label: "Assignments" },
];

const blankEditor: Editor = {
  id: null, name: "", priority: "10", enabled: true,
  absoluteTimeoutMinutes: "480", idleTimeoutMinutes: "30", reauthIntervalMinutes: "5",
  reauthFactors: "TOTP,FIDO2", sensitiveReauthWindowMinutes: "2", stepUpFactors: "TOTP,FIDO2",
  bindClient: true, maxConcurrentSessions: "0", rotateOnReauth: true,
  cookieSameSite: "Lax", roleIds: [], userIds: [], ipRules: [],
};

function toEditor(p: SessionPolicy): Editor {
  return {
    id: p.id, name: p.name, priority: String(p.priority), enabled: p.enabled,
    absoluteTimeoutMinutes: String(p.absoluteTimeoutMinutes),
    idleTimeoutMinutes: String(p.idleTimeoutMinutes),
    reauthIntervalMinutes: String(p.reauthIntervalMinutes),
    reauthFactors: p.reauthFactors,
    sensitiveReauthWindowMinutes: String(p.sensitiveReauthWindowMinutes), stepUpFactors: p.stepUpFactors,
    bindClient: p.bindClient,
    maxConcurrentSessions: String(p.maxConcurrentSessions), rotateOnReauth: p.rotateOnReauth,
    cookieSameSite: p.cookieSameSite,
    roleIds: [...p.assignedRoleIds], userIds: [...p.assignedUserIds],
    ipRules: [...p.ipRules].sort((a, b) => a.priority - b.priority).map((r) => ({ zoneId: r.zoneId, action: r.action })),
  };
}

/**
 * Okta-style, full-width session-policy editor (create at `session-policy/new`, edit at
 * `session-policy/:id`): a breadcrumb, tabs, two-column settings sections (title/description on the left,
 * controls on the right) and a sticky save bar. Network rules reference reusable Network Zones.
 */
export default function SessionPolicyDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isNew = !id;
  const [editor, setEditor] = useState<Editor | null>(isNew ? blankEditor : null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [zoneNames, setZoneNames] = useState<Record<string, string>>({});
  const [tab, setTab] = useState<Tab>("general");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [addKey, setAddKey] = useState(0);

  useEffect(() => {
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
    listZones().then((zs) => setZoneNames(Object.fromEntries(zs.map((z) => [z.id, z.name])))).catch(() => undefined);
  }, []);
  useEffect(() => {
    if (isNew) return;
    apiGet<Page<SessionPolicy>>("/api/admin/session-policies?size=100")
      .then((p) => {
        const found = p.items.find((x) => x.id === id);
        if (found) setEditor(toEditor(found));
        else setError("Policy not found.");
      })
      .catch((e) => setError(errorMessage(e)));
  }, [id, isNew]);

  const set = (patch: Partial<Editor>) => setEditor((e) => (e ? { ...e, ...patch } : e));

  /** Client-side check with a jump to the offending tab — the tabbed layout hides other tabs' fields. */
  function validate(e: Editor): { tab: Tab; message: string } | null {
    const intOf = (v: string) => (/^\d+$/.test(v.trim()) ? Number(v) : NaN);
    if (!e.name.trim()) return { tab: "general", message: "Name is required." };
    if (Number.isNaN(intOf(e.priority))) return { tab: "general", message: "Priority must be a whole number." };
    if (!(intOf(e.absoluteTimeoutMinutes) >= 1)) return { tab: "general", message: "Absolute timeout must be at least 1 minute." };
    if (!(intOf(e.idleTimeoutMinutes) >= 1)) return { tab: "general", message: "Idle timeout must be at least 1 minute." };
    if (Number.isNaN(intOf(e.maxConcurrentSessions))) return { tab: "general", message: "Max concurrent sessions must be a whole number (0 = unlimited)." };
    if (!(intOf(e.reauthIntervalMinutes) >= 1)) return { tab: "reauth", message: "Re-auth interval must be at least 1 minute." };
    if (!(intOf(e.sensitiveReauthWindowMinutes) >= 1)) return { tab: "reauth", message: "Step-up window must be at least 1 minute." };
    if (tokens(e.reauthFactors, ",").length === 0) return { tab: "reauth", message: "Pick at least one re-auth factor." };
    if (tokens(e.stepUpFactors, ",").length === 0) return { tab: "reauth", message: "Pick at least one step-up factor." };
    return null;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
    const invalid = validate(editor);
    if (invalid) { setError(invalid.message); setTab(invalid.tab); return; }
    setError(null); setBusy(true);
    const body = {
      name: editor.name,
      priority: Number(editor.priority),
      enabled: editor.enabled,
      absoluteTimeoutMinutes: Number(editor.absoluteTimeoutMinutes),
      idleTimeoutMinutes: Number(editor.idleTimeoutMinutes),
      reauthIntervalMinutes: Number(editor.reauthIntervalMinutes),
      reauthFactors: editor.reauthFactors,
      sensitiveReauthWindowMinutes: Number(editor.sensitiveReauthWindowMinutes),
      stepUpFactors: editor.stepUpFactors,
      bindClient: editor.bindClient,
      maxConcurrentSessions: Number(editor.maxConcurrentSessions),
      rotateOnReauth: editor.rotateOnReauth,
      cookieSameSite: editor.cookieSameSite,
      assignedRoleIds: editor.roleIds,
      assignedUserIds: editor.userIds,
      ipRules: editor.ipRules.map((r, i) => ({ zoneId: r.zoneId, action: r.action, priority: i })),
    };
    try {
      if (editor.id) await apiPut(`/api/admin/session-policies/${editor.id}`, body);
      else await apiPost("/api/admin/session-policies", body);
      navigate("/admin/session-policy");
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

  const crumbName = isNew ? "New policy" : (editor?.name || "Edit");

  return (
    <EditorPage<Tab>
      backTo="/admin/session-policy" backLabel="Session Policies" crumb={crumbName}
      title={isNew ? "New session policy" : (editor?.name ?? "…")}
      description="Session lifetimes, re-authentication, network access, and where this policy applies."
      tabs={TABS} activeTab={tab} onTab={setTab}
      error={error} formId="policy-form" onSubmit={submit} busy={busy}
      submitLabel={isNew ? "Create policy" : "Save changes"}
      onCancel={() => navigate("/admin/session-policy")} loading={!editor}
    >
      {editor && (
        <>
          {tab === "general" && <GeneralTab editor={editor} set={set} />}
          {tab === "reauth" && <ReauthTab editor={editor} set={set} />}
          {tab === "network" && (
            <NetworkTab editor={editor} set={set} zoneNames={zoneNames} addKey={addKey}
                        onAdd={(zoneId, label) => {
                          setZoneNames((m) => ({ ...m, [zoneId]: label }));
                          // One rule per zone: a duplicate row could never fire (first-match) yet would
                          // display as a meaningful rule — skip instead.
                          if (!editor.ipRules.some((r) => r.zoneId === zoneId)) {
                            set({ ipRules: [...editor.ipRules, { zoneId, action: "BLOCK" }] });
                          }
                          setAddKey((k) => k + 1);
                        }} />
          )}
          {tab === "assign" && <AssignTab editor={editor} set={set} roles={roles} />}
        </>
      )}
    </EditorPage>
  );
}

/** A checkbox chip group for the factor pickers. */
function FactorChips({ selected, onToggle }: { selected: string[]; onToggle: (f: string) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {REAUTH_FACTORS.map((f) => (
        <label key={f} className="flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
          <Checkbox checked={selected.includes(f)} onCheckedChange={() => onToggle(f)} /> {f}
        </label>
      ))}
    </div>
  );
}

function GeneralTab({ editor, set }: { editor: Editor; set: (p: Partial<Editor>) => void }) {
  return (
    <>
      <SettingsSection title="Basics" description="Identify the policy and set how it ranks against others.">
        <Field label="Name" hint={editor.id ? "The name can't be changed after creation." : undefined}>
          <Input value={editor.name} disabled={!!editor.id} onChange={(e) => set({ name: e.target.value })} />
        </Field>
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label="Priority" hint="Higher wins when several policies match a user.">
            <Input value={editor.priority} inputMode="numeric" onChange={(e) => set({ priority: e.target.value })} />
          </Field>
          <Toggle label="Enabled" hint="Disabled policies are ignored during resolution."
                  checked={editor.enabled} onChange={(v) => set({ enabled: v })} />
        </div>
      </SettingsSection>

      <SettingsSection title="Session lifetime" description="How long a session lasts before it must be re-established.">
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label="Absolute timeout (min)" hint="Max lifetime — forces a full re-auth.">
            <Input type="number" min={1} value={editor.absoluteTimeoutMinutes} onChange={(e) => set({ absoluteTimeoutMinutes: e.target.value })} />
          </Field>
          <Field label="Idle timeout (min)" hint="Expires after this much inactivity.">
            <Input type="number" min={1} value={editor.idleTimeoutMinutes} onChange={(e) => set({ idleTimeoutMinutes: e.target.value })} />
          </Field>
        </div>
        <Field label="Max concurrent sessions" hint="0 = unlimited; over the cap evicts the oldest session.">
          <Input type="number" min={0} value={editor.maxConcurrentSessions} onChange={(e) => set({ maxConcurrentSessions: e.target.value })} />
        </Field>
      </SettingsSection>

      <SettingsSection title="Session hardening" description="Defenses that bind and rotate the session cookie.">
        <Toggle label="Bind session to client" hint="Reject a session cookie replayed from a different browser or device."
                checked={editor.bindClient} onChange={(v) => set({ bindClient: v })} />
        <Toggle label="Rotate session id on re-auth" hint="Issue a fresh session id after a successful step-up re-authentication."
                checked={editor.rotateOnReauth} onChange={(v) => set({ rotateOnReauth: v })} />
      </SettingsSection>
    </>
  );
}

function ReauthTab({ editor, set }: { editor: Editor; set: (p: Partial<Editor>) => void }) {
  const factors = tokens(editor.reauthFactors, ",");
  const stepUp = tokens(editor.stepUpFactors, ",");
  const toggle = (list: string[], f: string) => (list.includes(f) ? list.filter((x) => x !== f) : [...list, f]);
  return (
    <>
      <SettingsSection title="Re-authentication"
                       description="Prompt for a fresh factor after the session has been idle for a while.">
        <Field label="Re-auth interval (min)" hint="After this much inactivity, re-authentication is required.">
          <Input type="number" min={1} value={editor.reauthIntervalMinutes} onChange={(e) => set({ reauthIntervalMinutes: e.target.value })} />
        </Field>
        <div className="space-y-2">
          <Label>Allowed factors</Label>
          <FactorChips selected={factors} onToggle={(f) => set({ reauthFactors: toggle(factors, f).join(",") })} />
        </div>
      </SettingsSection>

      <SettingsSection title="Sensitive actions"
                       description="Deletes, grants and key rotation need a stricter, more recent step-up — and can require a stronger factor.">
        <Field label="Step-up window (min)" hint="The step-up must be at least this recent for a sensitive action.">
          <Input type="number" min={1} value={editor.sensitiveReauthWindowMinutes} onChange={(e) => set({ sensitiveReauthWindowMinutes: e.target.value })} />
        </Field>
        <div className="space-y-2">
          <Label>Step-up factors</Label>
          <FactorChips selected={stepUp} onToggle={(f) => set({ stepUpFactors: toggle(stepUp, f).join(",") })} />
          <p className="text-xs text-muted-foreground">Can be stronger than the general factors — e.g. passkey (FIDO2) only.</p>
        </div>
      </SettingsSection>
    </>
  );
}

function NetworkTab({ editor, set, zoneNames, addKey, onAdd }:
    { editor: Editor; set: (p: Partial<Editor>) => void; zoneNames: Record<string, string>; addKey: number;
      onAdd: (zoneId: string, label: string) => void }) {
  const rules = editor.ipRules;
  const setRule = (i: number, action: string) => set({ ipRules: rules.map((r, j) => (j === i ? { ...r, action } : r)) });
  const removeRule = (i: number) => set({ ipRules: rules.filter((_, j) => j !== i) });
  const moveRule = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= rules.length) return;
    const next = [...rules];
    [next[i], next[j]] = [next[j], next[i]];
    set({ ipRules: next });
  };
  return (
    <SettingsSection
      title="Network zones"
      description="Allow or block reusable network zones. Rules are first-match, top to bottom — the first zone that contains the visitor's IP decides; no match means allowed.">
      {rules.length === 0 ? (
        <p className="text-sm text-muted-foreground">No network restriction — this policy's users may connect from anywhere.</p>
      ) : (
        <div className="space-y-2">
          {rules.map((r, i) => (
            <div key={i} className="flex items-center gap-2 rounded-md border p-2">
              <span className="w-5 text-center text-xs tabular-nums text-muted-foreground">{i + 1}</span>
              <Badge variant="muted" className="flex-1 justify-start truncate">{zoneNames[r.zoneId] ?? r.zoneId}</Badge>
              <Select className="w-28" value={r.action} onChange={(e) => setRule(i, e.target.value)}>
                <option value="ALLOW">Allow</option>
                <option value="BLOCK">Block</option>
              </Select>
              <Button type="button" variant="ghost" size="icon" disabled={i === 0} onClick={() => moveRule(i, -1)} aria-label="Move up"><ChevronUp /></Button>
              <Button type="button" variant="ghost" size="icon" disabled={i === rules.length - 1} onClick={() => moveRule(i, 1)} aria-label="Move down"><ChevronDown /></Button>
              <Button type="button" variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => removeRule(i)} aria-label="Remove"><Trash2 /></Button>
            </div>
          ))}
        </div>
      )}
      <div className="max-w-sm">
        <SearchSelect placeholder="Search zones to add…" fetcher={searchZones} resetKey={addKey}
                      onSelect={(s) => { if (s) onAdd(s.id, s.label); }} />
      </div>
      <div className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <span>
          Zones are defined in <Link to="/admin/network-zones" className="font-medium text-foreground hover:underline">Network Zones</Link>.
          A catch-all block should cover both IPv4 (<code>0.0.0.0/0</code>) and IPv6 (<code>::/0</code>).
        </span>
        <InfoHint label="How zone rules are evaluated">
          Rules are checked top to bottom. The first rule any of whose zone ranges contains the visitor's IP
          decides — <strong>Allow</strong> admits, <strong>Block</strong> denies. No match → allowed. Checked
          after sign-in and also on OIDC single sign-on.
        </InfoHint>
      </div>
    </SettingsSection>
  );
}

function AssignTab({ editor, set, roles }: { editor: Editor; set: (p: Partial<Editor>) => void; roles: Role[] }) {
  const toggle = (v: string) => (editor.roleIds.includes(v) ? editor.roleIds.filter((x) => x !== v) : [...editor.roleIds, v]);
  return (
    <SettingsSection
      title="Applies to"
      description="Target this policy at roles and/or users. Leave both empty to apply it to everyone (global). Cookie attributes are global and edited on the Default policy.">
      <div className="space-y-2">
        <Label>Roles</Label>
        <CheckboxGroup
          options={roles.map((r) => ({ value: r.id, label: r.name }))}
          selected={editor.roleIds} onToggle={(v) => set({ roleIds: toggle(v) })} emptyText="No roles"
        />
      </div>
      <div className="space-y-2">
        <Label>Users</Label>
        <UserMultiSelect selected={editor.userIds} onChange={(ids) => set({ userIds: ids })} placeholder="Search users to target…" />
      </div>
    </SettingsSection>
  );
}
