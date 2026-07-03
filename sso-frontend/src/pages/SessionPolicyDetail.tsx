import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, ChevronDown, ChevronUp, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut, errorMessage, type Page } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { InfoHint } from "@/components/InfoHint";
import { LoadingCard } from "@/components/states";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Field } from "@/components/form/fields";
import { UserMultiSelect } from "@/components/UserMultiSelect";
import { tokens } from "@/lib/utils";

interface IpRule {
  cidr: string;
  action: string; // "ALLOW" | "BLOCK"
  priority: number;
}
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
  ipRules: IpRule[];
}
interface Role { id: string; name: string }

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
  ipRules: { cidr: string; action: string }[]; // order = priority (top first)
}

const REAUTH_FACTORS = ["TOTP", "FIDO2", "PASSWORD", "EMAIL"];

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
    ipRules: p.ipRules.map((r) => ({ cidr: r.cidr, action: r.action })),
  };
}

/**
 * Full-page editor for a session policy (create at `session-policy/new`, edit at `session-policy/:id`).
 * Replaces the former modal — the added network (IP) rules section made it too large for a dialog.
 */
export default function SessionPolicyDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isNew = !id;
  const [editor, setEditor] = useState<Editor | null>(isNew ? blankEditor : null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
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

  if (!editor) {
    return (
      <>
        <BackHeader isNew={isNew} name="" />
        {error ? <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : <LoadingCard />}
      </>
    );
  }

  const set = (patch: Partial<Editor>) => setEditor((e) => (e ? { ...e, ...patch } : e));
  const toggle = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

  const factors = tokens(editor.reauthFactors, ",");
  const stepUpFactorList = tokens(editor.stepUpFactors, ",");
  const toggleFactor = (f: string) =>
    set({ reauthFactors: (factors.includes(f) ? factors.filter((x) => x !== f) : [...factors, f]).join(",") });
  const toggleStepUpFactor = (f: string) =>
    set({ stepUpFactors: (stepUpFactorList.includes(f) ? stepUpFactorList.filter((x) => x !== f) : [...stepUpFactorList, f]).join(",") });

  const rules = editor.ipRules;
  const setRule = (i: number, patch: Partial<{ cidr: string; action: string }>) =>
    set({ ipRules: rules.map((r, j) => (j === i ? { ...r, ...patch } : r)) });
  const addRule = () => set({ ipRules: [...rules, { cidr: "", action: "BLOCK" }] });
  const removeRule = (i: number) => set({ ipRules: rules.filter((_, j) => j !== i) });
  const moveRule = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= rules.length) return;
    const next = [...rules];
    [next[i], next[j]] = [next[j], next[i]];
    set({ ipRules: next });
  };

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
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
      ipRules: editor.ipRules.map((r, i) => ({ cidr: r.cidr.trim(), action: r.action, priority: i })),
    };
    try {
      if (editor.id) await apiPut(`/api/admin/session-policies/${editor.id}`, body);
      else await apiPost("/api/admin/session-policies", body);
      navigate("/admin/session-policy");
    } catch (e) {
      // A cancelled step-up maps to "" — keep the form as-is with no scary error.
      setError(errorMessage(e));
      setBusy(false);
    }
  }

  return (
    <>
      <BackHeader isNew={isNew} name={editor.name} />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      <Card className="max-w-3xl">
        <CardContent className="pt-6">
          <form onSubmit={submit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="sp-name">Name</Label>
              <Input id="sp-name" value={editor.name} disabled={!!editor.id}
                     onChange={(e) => set({ name: e.target.value })} required />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="sp-priority">Priority <span className="text-muted-foreground">(higher wins)</span></Label>
                <Input id="sp-priority" value={editor.priority} inputMode="numeric"
                       onChange={(e) => set({ priority: e.target.value })} />
              </div>
              <div className="flex items-center gap-2 pt-7">
                <Switch id="sp-enabled" checked={editor.enabled} onCheckedChange={(v) => set({ enabled: v })} />
                <Label htmlFor="sp-enabled">Enabled</Label>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <Field label="Absolute timeout (min)" hint="Max session lifetime — forces full re-auth.">
                <Input type="number" min={1} value={editor.absoluteTimeoutMinutes}
                       onChange={(e) => set({ absoluteTimeoutMinutes: e.target.value })} />
              </Field>
              <Field label="Idle timeout (min)" hint="Expires after this much inactivity.">
                <Input type="number" min={1} value={editor.idleTimeoutMinutes}
                       onChange={(e) => set({ idleTimeoutMinutes: e.target.value })} />
              </Field>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <Field label="Re-auth interval (min)" hint="After this much inactivity, re-authentication is required.">
                <Input type="number" min={1} value={editor.reauthIntervalMinutes}
                       onChange={(e) => set({ reauthIntervalMinutes: e.target.value })} />
              </Field>
              <Field label="Max concurrent sessions" hint="0 = unlimited; over the cap evicts the oldest.">
                <Input type="number" min={0} value={editor.maxConcurrentSessions}
                       onChange={(e) => set({ maxConcurrentSessions: e.target.value })} />
              </Field>
            </div>

            <div className="space-y-2">
              <Label>Allowed re-auth factors</Label>
              <div className="flex flex-wrap gap-2">
                {REAUTH_FACTORS.map((f) => (
                  <label key={f} className="flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
                    <Checkbox checked={factors.includes(f)} onCheckedChange={() => toggleFactor(f)} /> {f}
                  </label>
                ))}
              </div>
            </div>

            <div className="rounded-md border border-dashed p-3 space-y-3">
              <p className="text-sm font-medium">Sensitive actions (delete / grant / key rotation)</p>
              <Field label="Step-up re-auth window (min)" hint="These actions need a deliberate re-auth this recent — stricter than the interval above.">
                <Input type="number" min={1} value={editor.sensitiveReauthWindowMinutes}
                       onChange={(e) => set({ sensitiveReauthWindowMinutes: e.target.value })} />
              </Field>
              <div className="space-y-2">
                <Label>Step-up factors</Label>
                <div className="flex flex-wrap gap-2">
                  {REAUTH_FACTORS.map((f) => (
                    <label key={f} className="flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
                      <Checkbox checked={stepUpFactorList.includes(f)} onCheckedChange={() => toggleStepUpFactor(f)} /> {f}
                    </label>
                  ))}
                </div>
                <p className="text-xs text-muted-foreground">Can be stronger than the general factors — e.g. passkey (FIDO2) only.</p>
              </div>
            </div>

            {/* Network (IP) access rules — first-match, ordered top-first. */}
            <div className="rounded-md border p-3 space-y-3">
              <div className="flex items-center gap-1.5">
                <p className="text-sm font-medium">Network access (IP rules)</p>
                <InfoHint label="How IP rules are evaluated">
                  Rules are checked top to bottom. The first rule whose range contains the visitor's IP decides —
                  <strong> Allow</strong> admits, <strong>Block</strong> denies. If no rule matches, access is allowed.
                  Put allow ranges above and block ranges below (e.g. Allow your office, then Block everything).
                  A catch-all block needs BOTH <code>0.0.0.0/0</code> (IPv4) and <code>::/0</code> (IPv6) — a rule
                  only matches its own address family. Checked after sign-in, so it only restricts users this
                  policy applies to.
                </InfoHint>
              </div>
              {rules.length === 0 && (
                <p className="text-xs text-muted-foreground">No IP restriction — this policy's users may connect from anywhere.</p>
              )}
              <div className="space-y-2">
                {rules.map((r, i) => (
                  <div key={i} className="flex items-center gap-2">
                    <span className="w-5 text-center text-xs text-muted-foreground">{i + 1}</span>
                    <Input className="flex-1" value={r.cidr} placeholder="203.0.113.0/24 or 2001:db8::/32"
                           onChange={(e) => setRule(i, { cidr: e.target.value })} />
                    <Select className="w-28" value={r.action} onChange={(e) => setRule(i, { action: e.target.value })}>
                      <option value="ALLOW">Allow</option>
                      <option value="BLOCK">Block</option>
                    </Select>
                    <Button type="button" variant="ghost" size="icon" disabled={i === 0} onClick={() => moveRule(i, -1)} aria-label="Move up"><ChevronUp /></Button>
                    <Button type="button" variant="ghost" size="icon" disabled={i === rules.length - 1} onClick={() => moveRule(i, 1)} aria-label="Move down"><ChevronDown /></Button>
                    <Button type="button" variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => removeRule(i)} aria-label="Remove"><Trash2 /></Button>
                  </div>
                ))}
              </div>
              <Button type="button" variant="outline" size="sm" onClick={addRule}><Plus /> Add rule</Button>
            </div>

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

            <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
              Leave roles &amp; users empty to apply this policy to <strong>everyone</strong> (global).
              Cookie attributes (SameSite/Secure) are global and edited on the Default policy's list page.
            </p>

            <div className="space-y-2">
              <Label>Assign to roles</Label>
              <div className="flex flex-wrap gap-3 rounded-md border p-3">
                {roles.length === 0 ? <span className="text-sm text-muted-foreground">none</span> : roles.map((r) => (
                  <label key={r.id} className="flex items-center gap-2 text-sm">
                    <Checkbox checked={editor.roleIds.includes(r.id)}
                              onCheckedChange={() => set({ roleIds: toggle(editor.roleIds, r.id) })} /> {r.name}
                  </label>
                ))}
              </div>
            </div>

            <div className="space-y-2">
              <Label>Assign to users</Label>
              <UserMultiSelect selected={editor.userIds} onChange={(ids) => set({ userIds: ids })}
                               placeholder="Search users to target…" />
            </div>

            <div className="flex justify-end gap-2 border-t pt-4">
              <Button type="button" variant="outline" asChild><Link to="/admin/session-policy">Cancel</Link></Button>
              <Button type="submit" disabled={busy}>{editor.id ? "Save changes" : "Create policy"}</Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </>
  );
}

function BackHeader({ isNew, name }: { isNew: boolean; name: string }) {
  return (
    <>
      <Link to="/admin/session-policy" className="mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> Back to session policies
      </Link>
      <PageHeader
        title={isNew ? "New session policy" : `Edit policy${name ? `: ${name}` : ""}`}
        description="Session lifetime, step-up re-auth, network (IP) rules, concurrency and where it applies."
      />
    </>
  );
}
