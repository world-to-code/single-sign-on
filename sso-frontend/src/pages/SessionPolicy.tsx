import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Pencil, Plus, Save, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut, type Page } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { Field } from "@/components/form/fields";
import { UserMultiSelect } from "@/components/UserMultiSelect";
import { usersByIds } from "@/groups";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";
import { tokens } from "@/lib/utils";

interface SessionPolicy {
  id: string;
  name: string;
  priority: number;
  enabled: boolean;
  absoluteTimeoutMinutes: number;
  idleTimeoutMinutes: number;
  reauthIntervalMinutes: number;
  reauthFactors: string;
  bindClient: boolean;
  maxConcurrentSessions: number;
  rotateOnReauth: boolean;
  cookieSameSite: string;
  assignedUserIds: string[];
  assignedRoleIds: string[];
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
  bindClient: boolean;
  maxConcurrentSessions: string;
  rotateOnReauth: boolean;
  cookieSameSite: string;
  roleIds: string[];
  userIds: string[];
}

const REAUTH_FACTORS = ["TOTP", "FIDO2", "PASSWORD", "EMAIL"];

const blankEditor: Editor = {
  id: null, name: "", priority: "10", enabled: true,
  absoluteTimeoutMinutes: "480", idleTimeoutMinutes: "30", reauthIntervalMinutes: "5",
  reauthFactors: "TOTP,FIDO2", bindClient: true, maxConcurrentSessions: "0", rotateOnReauth: true,
  cookieSameSite: "Lax", roleIds: [], userIds: [],
};

export default function SessionPolicyPage() {
  const confirmDelete = useDeleteConfirm();
  const [policies, setPolicies] = useState<SessionPolicy[] | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [userNames, setUserNames] = useState<Record<string, string>>({});

  const {
    editor, set, setEditor, open, setOpen, error, setError, openCreate, openEdit, save,
  } = useEditorForm<Editor>({
    blank: blankEditor,
    toRequest: (e) => ({
      name: e.name,
      priority: Number(e.priority),
      enabled: e.enabled,
      absoluteTimeoutMinutes: Number(e.absoluteTimeoutMinutes),
      idleTimeoutMinutes: Number(e.idleTimeoutMinutes),
      reauthIntervalMinutes: Number(e.reauthIntervalMinutes),
      reauthFactors: e.reauthFactors,
      bindClient: e.bindClient,
      maxConcurrentSessions: Number(e.maxConcurrentSessions),
      rotateOnReauth: e.rotateOnReauth,
      cookieSameSite: e.cookieSameSite,
      assignedRoleIds: e.roleIds,
      assignedUserIds: e.userIds,
    }),
    create: (body) => apiPost("/api/admin/session-policies", body),
    update: (id, body) => apiPut(`/api/admin/session-policies/${id}`, body),
    onSaved: reload,
  });

  function reload() {
    apiGet<Page<SessionPolicy>>("/api/admin/session-policies?size=100")
      .then((p) => setPolicies(p.items)).catch((e) => setError(String(e)));
  }
  useEffect(() => {
    reload();
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
  }, []);

  // Resolve the user ids assigned across the loaded policies to names for the table (no all-users load).
  useEffect(() => {
    const ids = [...new Set((policies ?? []).flatMap((p) => p.assignedUserIds))];
    usersByIds(ids)
      .then((sugs) => setUserNames(Object.fromEntries(sugs.map((s) => [s.id, s.label]))))
      .catch(() => undefined);
  }, [policies]);

  function toggle(list: string[], id: string): string[] {
    return list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
  }

  const factors = tokens(editor.reauthFactors, ",");
  function toggleFactor(f: string) {
    const next = factors.includes(f) ? factors.filter((x) => x !== f) : [...factors, f];
    set({ reauthFactors: next.join(",") });
  }

  function editPolicy(p: SessionPolicy) {
    openEdit({
      id: p.id, name: p.name, priority: String(p.priority), enabled: p.enabled,
      absoluteTimeoutMinutes: String(p.absoluteTimeoutMinutes),
      idleTimeoutMinutes: String(p.idleTimeoutMinutes),
      reauthIntervalMinutes: String(p.reauthIntervalMinutes),
      reauthFactors: p.reauthFactors, bindClient: p.bindClient,
      maxConcurrentSessions: String(p.maxConcurrentSessions), rotateOnReauth: p.rotateOnReauth,
      cookieSameSite: p.cookieSameSite,
      roleIds: [...p.assignedRoleIds], userIds: [...p.assignedUserIds],
    });
  }

  async function remove(p: SessionPolicy) {
    await confirmDelete({
      title: "Delete session policy?",
      description: `"${p.name}" will be permanently removed.`,
      path: `/api/admin/session-policies/${p.id}`,
      onDeleted: reload,
    });
  }

  const roleName = (id: string) => roles.find((r) => r.id === id)?.name ?? id;
  const userName = (id: string) => userNames[id] ?? id;
  const defaultPolicy = policies?.find((p) => p.name === "Default") ?? null;

  return (
    <>
      <PageHeader
        title="Session Policy"
        description="Named session policies applied per role or user — the highest-priority matching policy wins."
        actions={<Button onClick={openCreate}><Plus /> New policy</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          The <strong>highest-priority</strong> matching policy wins. Assign a policy to roles/users to target them,
          or <strong>leave the assignment empty to apply it to everyone</strong> (global). The built-in Default is the
          lowest-priority fallback (and supplies the global cookie settings below).
        </AlertDescription>
      </Alert>

      <DataList
        data={policies}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title="No policies yet" hint="Create a session policy to target a role or user." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Priority</TableHead>
                <TableHead>Enabled</TableHead>
                <TableHead>Absolute / Idle</TableHead>
                <TableHead>Max sessions</TableHead>
                <TableHead>Roles</TableHead>
                <TableHead>Users</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((p) => (
                <TableRow key={p.id}>
                  <TableCell className="font-medium">
                    <div className="flex flex-wrap items-center gap-1.5">
                      {p.name}
                      {(p.assignedRoleIds.length === 0 && p.assignedUserIds.length === 0) && p.name !== "Default" && (
                        <Badge variant="default">Global</Badge>
                      )}
                      {p.rotateOnReauth && <Badge variant="muted">Rotate on re-auth</Badge>}
                    </div>
                  </TableCell>
                  <TableCell><Badge variant="muted">{p.priority}</Badge></TableCell>
                  <TableCell>
                    <Badge variant={p.enabled ? "success" : "muted"}>{p.enabled ? "Enabled" : "Disabled"}</Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {p.absoluteTimeoutMinutes}m / {p.idleTimeoutMinutes}m
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {p.maxConcurrentSessions === 0 ? "Unlimited" : p.maxConcurrentSessions}
                  </TableCell>
                  <TableCell className="text-muted-foreground">{p.assignedRoleIds.map(roleName).join(", ") || "—"}</TableCell>
                  <TableCell className="text-muted-foreground">{p.assignedUserIds.map(userName).join(", ") || "—"}</TableCell>
                  <TableCell className="text-right">
                    {p.name !== "Default" ? (
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" onClick={() => editPolicy(p)}><Pencil /></Button>
                        <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(p)}><Trash2 /></Button>
                      </div>
                    ) : (
                      <Badge variant="outline">Built-in</Badge>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      {defaultPolicy && <CookieCard policy={defaultPolicy} onSaved={reload} />}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editor.id ? `Edit policy: ${editor.name}` : "Create session policy"}</DialogTitle>
            <DialogDescription>Session lifetime, step-up re-auth, concurrency limits and where it applies.</DialogDescription>
          </DialogHeader>

          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
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
              <Field label="Re-auth interval (min)" hint="After this, sensitive actions require re-auth.">
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
              Cookie attributes (SameSite/Secure) are global and edited on the Default below.
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

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { setEditor(blankEditor); setOpen(false); }}>Cancel</Button>
              <Button type="submit">{editor.id ? "Save changes" : "Create policy"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

/** Global session-cookie attributes — applied for every session, so they live on the Default policy. */
function CookieCard({ policy, onSaved }: { policy: SessionPolicy; onSaved: () => void }) {
  const [sameSite, setSameSite] = useState(policy.cookieSameSite);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setSameSite(policy.cookieSameSite);
  }, [policy.cookieSameSite]);

  async function save(event: FormEvent) {
    event.preventDefault();
    setStatus(null); setError(null); setBusy(true);
    try {
      await apiPut(`/api/admin/session-policies/${policy.id}`, {
        name: policy.name,
        priority: policy.priority,
        enabled: policy.enabled,
        absoluteTimeoutMinutes: policy.absoluteTimeoutMinutes,
        idleTimeoutMinutes: policy.idleTimeoutMinutes,
        reauthIntervalMinutes: policy.reauthIntervalMinutes,
        reauthFactors: policy.reauthFactors,
        bindClient: policy.bindClient,
        maxConcurrentSessions: policy.maxConcurrentSessions,
        rotateOnReauth: policy.rotateOnReauth,
        cookieSameSite: sameSite,
        assignedRoleIds: policy.assignedRoleIds,
        assignedUserIds: policy.assignedUserIds,
      });
      setStatus("Cookie settings saved.");
      onSaved();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mt-6 max-w-xl">
      <CardHeader>
        <CardTitle>Session cookie (global)</CardTitle>
        <CardDescription>
          Attributes applied to the JSESSIONID cookie. These are global — the cookie is issued before
          the user is known, so only the Default policy's settings take effect.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {status && <Alert variant="success" className="mb-4"><AlertDescription>{status}</AlertDescription></Alert>}
        {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}
        <form onSubmit={save} className="space-y-4">
          <Field label="SameSite" hint="Cross-site cookie policy.">
            <Select value={sameSite} onChange={(e) => setSameSite(e.target.value)}>
              <option>Lax</option><option>Strict</option><option>None</option>
            </Select>
          </Field>
          <p className="text-sm text-muted-foreground">
            The cookie's <strong>Secure</strong> (HTTPS-only) attribute is enforced by deployment
            config (<code>server.servlet.session.cookie.secure</code>) in production, not here.
          </p>
          <Button type="submit" disabled={busy}><Save /> Save cookie settings</Button>
        </form>
      </CardContent>
    </Card>
  );
}
