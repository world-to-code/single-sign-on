import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, ChevronDown, ChevronUp, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut, errorMessage, type Page } from "../api";
import { listZones, searchZones } from "@/zones";
import { PageHeader } from "@/components/PageHeader";
import { InfoHint } from "@/components/InfoHint";
import { SearchSelect } from "@/components/SearchSelect";
import { LoadingCard } from "@/components/states";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";
import { tokens } from "@/lib/utils";
import { Field } from "@/components/form/fields";
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
 * Okta-style, full-width, TABBED session-policy editor (create at `session-policy/new`, edit at
 * `session-policy/:id`). Network rules reference reusable Network Zones (searched/picked, not inline CIDRs).
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

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
    if (!editor.name.trim()) { setError("Name is required."); setTab("general"); return; }
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

  if (!editor) {
    return (
      <>
        <BackHeader isNew={isNew} name="" busy onCancel={() => navigate("/admin/session-policy")} />
        {error ? <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : <LoadingCard />}
      </>
    );
  }

  return (
    <>
      <BackHeader isNew={isNew} name={editor.name} busy={busy} onCancel={() => navigate("/admin/session-policy")} />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      <div className="mb-4 flex gap-1 border-b">
        {TABS.map((t) => (
          <button key={t.key} type="button" onClick={() => setTab(t.key)}
                  className={cn("-mb-px border-b-2 px-4 py-2 text-sm font-medium",
                    tab === t.key ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground")}>
            {t.label}
          </button>
        ))}
      </div>

      <Card className="max-w-3xl">
        <CardContent className="pt-6">
          <form id="policy-form" onSubmit={submit} className="space-y-4">
            {tab === "general" && <GeneralTab editor={editor} set={set} />}
            {tab === "reauth" && <ReauthTab editor={editor} set={set} />}
            {tab === "network" && (
              <NetworkTab editor={editor} set={set} zoneNames={zoneNames} addKey={addKey}
                          onAdd={(zoneId, label) => {
                            setZoneNames((m) => ({ ...m, [zoneId]: label }));
                            set({ ipRules: [...editor.ipRules, { zoneId, action: "BLOCK" }] });
                            setAddKey((k) => k + 1);
                          }} />
            )}
            {tab === "assign" && <AssignTab editor={editor} set={set} roles={roles} />}
          </form>
        </CardContent>
      </Card>
    </>
  );
}

function BackHeader({ isNew, name, busy, onCancel }:
    { isNew: boolean; name: string; busy: boolean; onCancel: () => void }) {
  return (
    <>
      <Link to="/admin/session-policy" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> Session Policies{name ? ` / ${name}` : ""}
      </Link>
      <PageHeader
        title={isNew ? "New session policy" : `Edit policy${name ? `: ${name}` : ""}`}
        description="Lifetimes, re-authentication, network (zone) rules, and where the policy applies."
        actions={
          <div className="flex gap-2">
            <Button type="button" variant="outline" onClick={onCancel}>Cancel</Button>
            <Button form="policy-form" type="submit" disabled={busy}>{isNew ? "Create policy" : "Save changes"}</Button>
          </div>
        }
      />
    </>
  );
}

function GeneralTab({ editor, set }: { editor: Editor; set: (p: Partial<Editor>) => void }) {
  return (
    <>
      <div className="space-y-2">
        <Label htmlFor="sp-name">Name</Label>
        <Input id="sp-name" value={editor.name} disabled={!!editor.id} onChange={(e) => set({ name: e.target.value })} />
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="sp-priority">Priority <span className="text-muted-foreground">(higher wins)</span></Label>
          <Input id="sp-priority" value={editor.priority} inputMode="numeric" onChange={(e) => set({ priority: e.target.value })} />
        </div>
        <div className="flex items-center gap-2 pt-7">
          <Switch id="sp-enabled" checked={editor.enabled} onCheckedChange={(v) => set({ enabled: v })} />
          <Label htmlFor="sp-enabled">Enabled</Label>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Absolute timeout (min)" hint="Max session lifetime — forces full re-auth.">
          <Input type="number" min={1} value={editor.absoluteTimeoutMinutes} onChange={(e) => set({ absoluteTimeoutMinutes: e.target.value })} />
        </Field>
        <Field label="Idle timeout (min)" hint="Expires after this much inactivity.">
          <Input type="number" min={1} value={editor.idleTimeoutMinutes} onChange={(e) => set({ idleTimeoutMinutes: e.target.value })} />
        </Field>
      </div>
      <Field label="Max concurrent sessions" hint="0 = unlimited; over the cap evicts the oldest.">
        <Input type="number" min={0} value={editor.maxConcurrentSessions} onChange={(e) => set({ maxConcurrentSessions: e.target.value })} />
      </Field>
      <div className="flex items-center justify-between rounded-md border p-3">
        <div>
          <Label htmlFor="sp-bind">Bind session to client</Label>
          <p className="text-xs text-muted-foreground">Reject a session cookie replayed from a different browser/device.</p>
        </div>
        <Switch id="sp-bind" checked={editor.bindClient} onCheckedChange={(v) => set({ bindClient: v })} />
      </div>
      <div className="flex items-center justify-between rounded-md border p-3">
        <div>
          <Label htmlFor="sp-rotate">Rotate session id on re-auth</Label>
          <p className="text-xs text-muted-foreground">Issue a fresh session id after a successful step-up re-authentication.</p>
        </div>
        <Switch id="sp-rotate" checked={editor.rotateOnReauth} onCheckedChange={(v) => set({ rotateOnReauth: v })} />
      </div>
    </>
  );
}

function ReauthTab({ editor, set }: { editor: Editor; set: (p: Partial<Editor>) => void }) {
  const factors = tokens(editor.reauthFactors, ",");
  const stepUp = tokens(editor.stepUpFactors, ",");
  const toggle = (list: string[], f: string) => (list.includes(f) ? list.filter((x) => x !== f) : [...list, f]);
  return (
    <>
      <Field label="Re-auth interval (min)" hint="After this much inactivity, re-authentication is required.">
        <Input type="number" min={1} value={editor.reauthIntervalMinutes} onChange={(e) => set({ reauthIntervalMinutes: e.target.value })} />
      </Field>
      <div className="space-y-2">
        <Label>Allowed re-auth factors</Label>
        <div className="flex flex-wrap gap-2">
          {REAUTH_FACTORS.map((f) => (
            <label key={f} className="flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
              <Checkbox checked={factors.includes(f)} onCheckedChange={() => set({ reauthFactors: toggle(factors, f).join(",") })} /> {f}
            </label>
          ))}
        </div>
      </div>
      <div className="rounded-md border border-dashed p-3 space-y-3">
        <p className="text-sm font-medium">Sensitive actions (delete / grant / key rotation)</p>
        <Field label="Step-up re-auth window (min)" hint="These actions need a deliberate re-auth this recent — stricter than the interval above.">
          <Input type="number" min={1} value={editor.sensitiveReauthWindowMinutes} onChange={(e) => set({ sensitiveReauthWindowMinutes: e.target.value })} />
        </Field>
        <div className="space-y-2">
          <Label>Step-up factors</Label>
          <div className="flex flex-wrap gap-2">
            {REAUTH_FACTORS.map((f) => (
              <label key={f} className="flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
                <Checkbox checked={stepUp.includes(f)} onCheckedChange={() => set({ stepUpFactors: toggle(stepUp, f).join(",") })} /> {f}
              </label>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">Can be stronger than the general factors — e.g. passkey (FIDO2) only.</p>
        </div>
      </div>
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
    <>
      <div className="flex items-center gap-1.5">
        <Label>Network zone rules</Label>
        <InfoHint label="How zone rules are evaluated">
          Rules are checked top to bottom. The first rule any of whose zone ranges contains the visitor's IP
          decides — <strong>Allow</strong> admits, <strong>Block</strong> denies. If no rule matches, access is
          allowed. Put allow zones above and block zones below. A catch-all block zone should cover both IPv4
          (<code>0.0.0.0/0</code>) and IPv6 (<code>::/0</code>). Checked after sign-in and also on OIDC SSO.
        </InfoHint>
      </div>
      {rules.length === 0 && (
        <p className="text-xs text-muted-foreground">No network restriction — this policy's users may connect from anywhere.</p>
      )}
      <div className="space-y-2">
        {rules.map((r, i) => (
          <div key={i} className="flex items-center gap-2">
            <span className="w-5 text-center text-xs text-muted-foreground">{i + 1}</span>
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
      <div className="max-w-sm">
        <SearchSelect placeholder="Search zones to add…" fetcher={searchZones} resetKey={addKey}
                      onSelect={(s) => { if (s) onAdd(s.id, s.label); }} />
      </div>
      <p className="text-xs text-muted-foreground">
        Zones are defined in <strong>Network Zones</strong>. Create them there, then add them here.
      </p>
    </>
  );
}

function AssignTab({ editor, set, roles }: { editor: Editor; set: (p: Partial<Editor>) => void; roles: Role[] }) {
  const toggle = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  return (
    <>
      <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
        Leave roles &amp; users empty to apply this policy to <strong>everyone</strong> (global). Cookie
        attributes are global and edited on the Default policy's list page.
      </p>
      <div className="space-y-2">
        <Label>Assign to roles</Label>
        <div className="flex flex-wrap gap-3 rounded-md border p-3">
          {roles.length === 0 ? <span className="text-sm text-muted-foreground">none</span> : roles.map((r) => (
            <label key={r.id} className="flex items-center gap-2 text-sm">
              <Checkbox checked={editor.roleIds.includes(r.id)} onCheckedChange={() => set({ roleIds: toggle(editor.roleIds, r.id) })} /> {r.name}
            </label>
          ))}
        </div>
      </div>
      <div className="space-y-2">
        <Label>Assign to users</Label>
        <UserMultiSelect selected={editor.userIds} onChange={(ids) => set({ userIds: ids })} placeholder="Search users to target…" />
      </div>
    </>
  );
}
