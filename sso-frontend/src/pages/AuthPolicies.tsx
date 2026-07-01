import { useEffect, useState } from "react";
import { ArrowDown, ChevronDown, ChevronRight, ChevronUp, Pencil, Plus, Trash2, X } from "lucide-react";
import { apiGet, apiPost, apiPut } from "../api";
import { FACTORS, factorMeta } from "@/factors";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface Policy {
  id: string;
  name: string;
  priority: number;
  enabled: boolean;
  appliesToLogin: boolean;
  allowEnrollmentAtLogin: boolean;
  steps: string[][];
  assignedUserIds: string[];
  assignedRoleIds: string[];
  stepUpFreshnessMinutes: number;
}
interface Role { id: string; name: string }
interface User { id: string; username: string }

interface Editor {
  id: string | null;
  name: string;
  priority: string;
  enabled: boolean;
  appliesToLogin: boolean;
  allowEnrollmentAtLogin: boolean;
  steps: string[][];
  roleIds: string[];
  userIds: string[];
  stepUpFreshnessMinutes: string;
}

const blankEditor: Editor = { id: null, name: "", priority: "10", enabled: true, appliesToLogin: true, allowEnrollmentAtLogin: true, steps: [["PASSWORD"], ["TOTP", "EMAIL"]], roleIds: [], userIds: [], stepUpFreshnessMinutes: "15" };

/** Visual factor-chain builder: ordered steps, each an "any one of" set of factors (Okta-style). */
function StepsBuilder({ steps, onChange }: { steps: string[][]; onChange: (s: string[][]) => void }) {
  const setStep = (i: number, factors: string[]) => onChange(steps.map((s, idx) => (idx === i ? factors : s)));
  const removeStep = (i: number) => onChange(steps.filter((_, idx) => idx !== i));
  const moveStep = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= steps.length) return;
    const next = [...steps];
    [next[i], next[j]] = [next[j], next[i]];
    onChange(next);
  };
  const addStep = () => {
    const used = steps.flat();
    onChange([...steps, [FACTORS.find((f) => !used.includes(f)) ?? FACTORS[1]]]);
  };

  return (
    <div className="space-y-2">
      <Label>Sign-on steps <span className="text-muted-foreground">(verified in order)</span></Label>
      <div className="space-y-1">
        {steps.map((step, i) => {
          const remaining = FACTORS.filter((f) => !step.includes(f));
          return (
            <div key={i}>
              {i > 0 && (
                <div className="flex items-center gap-1.5 py-0.5 pl-3 text-xs font-medium text-muted-foreground">
                  <ArrowDown className="size-3" /> then
                </div>
              )}
              <div className="rounded-lg border bg-card p-3">
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Step {i + 1}</span>
                  <div className="flex items-center gap-0.5">
                    <Button type="button" variant="ghost" size="icon" className="size-7" disabled={i === 0} onClick={() => moveStep(i, -1)}><ChevronUp className="size-4" /></Button>
                    <Button type="button" variant="ghost" size="icon" className="size-7" disabled={i === steps.length - 1} onClick={() => moveStep(i, 1)}><ChevronDown className="size-4" /></Button>
                    <Button type="button" variant="ghost" size="icon" className="size-7 text-muted-foreground hover:text-destructive" disabled={steps.length === 1} onClick={() => removeStep(i)}><Trash2 className="size-4" /></Button>
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-1.5">
                  {step.length === 0 && <span className="text-xs text-destructive">pick a factor</span>}
                  {step.map((f, fi) => {
                    const meta = factorMeta(f);
                    const Icon = meta.icon;
                    return (
                      <span key={f} className="flex items-center gap-1.5">
                        {fi > 0 && <span className="text-xs font-medium text-muted-foreground">or</span>}
                        <span className="inline-flex items-center gap-1.5 rounded-md border bg-background py-1 pl-2 pr-1 text-sm">
                          <Icon className="size-3.5 text-primary" />
                          {meta.label}
                          <button type="button" className="rounded text-muted-foreground hover:text-destructive" onClick={() => setStep(i, step.filter((x) => x !== f))}><X className="size-3.5" /></button>
                        </span>
                      </span>
                    );
                  })}
                  {remaining.length > 0 && (
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button type="button" variant="outline" size="sm" className="h-7 gap-1 border-dashed">
                          <Plus className="size-3.5" /> {step.length === 0 ? "Add factor" : "or"}
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="start">
                        {remaining.map((f) => {
                          const meta = factorMeta(f);
                          const Icon = meta.icon;
                          return (
                            <DropdownMenuItem key={f} onClick={() => setStep(i, [...step, f])}>
                              <Icon className="size-4" /> {meta.label}
                            </DropdownMenuItem>
                          );
                        })}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
      <Button type="button" variant="outline" size="sm" className="w-full border-dashed" onClick={addStep}>
        <Plus className="size-4" /> Add step
      </Button>
      <p className="text-xs text-muted-foreground">
        Steps are required <strong>in order</strong>. Two+ factors in one step means the user may use <strong>any one</strong> of them.
      </p>
    </div>
  );
}

export default function AuthPolicies() {
  const confirmDelete = useDeleteConfirm();
  const [policies, setPolicies] = useState<Policy[] | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [users, setUsers] = useState<User[]>([]);

  const {
    editor, set, setEditor, open, setOpen, error, setError, openCreate, openEdit, save,
  } = useEditorForm<Editor>({
    blank: blankEditor,
    toRequest: (e) => ({
      name: e.name,
      priority: Number(e.priority),
      enabled: e.enabled,
      appliesToLogin: e.appliesToLogin,
      allowEnrollmentAtLogin: e.allowEnrollmentAtLogin,
      steps: e.steps.map((s) => [...s]).filter((s) => s.length > 0),
      assignedRoleIds: e.roleIds,
      assignedUserIds: e.userIds,
      stepUpFreshnessMinutes: Number(e.stepUpFreshnessMinutes) || 15,
    }),
    create: (body) => apiPost("/api/admin/auth-policies", body),
    update: (id, body) => apiPut(`/api/admin/auth-policies/${id}`, body),
    onSaved: reload,
  });

  function reload() {
    apiGet<Policy[]>("/api/admin/auth-policies").then(setPolicies).catch((e) => setError(String(e)));
  }
  useEffect(() => {
    reload();
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
    apiGet<User[]>("/api/admin/users").then(setUsers).catch(() => undefined);
  }, []);

  function toggle(list: string[], id: string): string[] {
    return list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
  }

  function editPolicy(p: Policy) {
    openEdit({
      id: p.id, name: p.name, priority: String(p.priority), enabled: p.enabled, appliesToLogin: p.appliesToLogin,
      allowEnrollmentAtLogin: p.allowEnrollmentAtLogin,
      steps: p.steps.map((s) => [...s]), roleIds: [...p.assignedRoleIds], userIds: [...p.assignedUserIds],
      stepUpFreshnessMinutes: String(p.stepUpFreshnessMinutes ?? 15),
    });
  }

  async function remove(p: Policy) {
    await confirmDelete({
      title: "Delete policy?",
      description: `"${p.name}" will be permanently removed.`,
      path: `/api/admin/auth-policies/${p.id}`,
      onDeleted: reload,
    });
  }

  const roleName = (id: string) => roles.find((r) => r.id === id)?.name ?? id;
  const userName = (id: string) => users.find((u) => u.id === id)?.username ?? id;

  return (
    <>
      <PageHeader
        title="Authentication Policies"
        description="Factor chains applied per role or user — the highest-priority matching policy wins."
        actions={<Button onClick={openCreate}><Plus /> New policy</Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          The <strong>highest-priority</strong> matching policy wins. Assign a policy to roles/users to target them,
          or <strong>leave the assignment empty to apply it to everyone</strong> (global). The built-in Default is the
          lowest-priority fallback.
        </AlertDescription>
      </Alert>

      <DataList
        data={policies}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title="No policies yet" hint="Create a policy to require specific factors for a role or user." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Priority</TableHead>
                <TableHead>Enabled</TableHead>
                <TableHead>Chain</TableHead>
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
                      {!p.appliesToLogin
                        ? <Badge variant="outline">App-only</Badge>
                        : (p.assignedRoleIds.length === 0 && p.assignedUserIds.length === 0)
                          ? <Badge variant="default">Global</Badge> : null}
                      {p.appliesToLogin && !p.allowEnrollmentAtLogin && <Badge variant="muted">No self-enroll</Badge>}
                    </div>
                  </TableCell>
                  <TableCell><Badge variant="muted">{p.priority}</Badge></TableCell>
                  <TableCell>
                    <Badge variant={p.enabled ? "success" : "muted"}>{p.enabled ? "Enabled" : "Disabled"}</Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap items-center gap-1">
                      {p.steps.map((s, i) => (
                        <span key={i} className="flex items-center gap-1">
                          {i > 0 && <ChevronRight className="size-3 text-muted-foreground" />}
                          <Badge variant="secondary">{s.join(" or ")}</Badge>
                        </span>
                      ))}
                    </div>
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

      <p className="mt-4 text-sm text-muted-foreground">Factors: PASSWORD, TOTP, EMAIL, FIDO2.</p>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editor.id ? `Edit policy: ${editor.name}` : "Create policy"}</DialogTitle>
            <DialogDescription>Define the ordered factor chain and where it applies.</DialogDescription>
          </DialogHeader>

          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="pol-name">Name</Label>
              <Input id="pol-name" value={editor.name} disabled={!!editor.id}
                     onChange={(e) => set({ name: e.target.value })} required />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="pol-priority">Priority <span className="text-muted-foreground">(higher wins)</span></Label>
                <Input id="pol-priority" value={editor.priority} inputMode="numeric"
                       onChange={(e) => set({ priority: e.target.value })} />
              </div>
              <div className="flex items-center gap-2 pt-7">
                <Switch id="pol-enabled" checked={editor.enabled} onCheckedChange={(v) => set({ enabled: v })} />
                <Label htmlFor="pol-enabled">Enabled</Label>
              </div>
            </div>

            <StepsBuilder steps={editor.steps} onChange={(steps) => set({ steps })} />

            <div className="flex items-center justify-between rounded-md border p-3">
              <div>
                <Label htmlFor="pol-login">Use for login (sign-on policy)</Label>
                <p className="text-xs text-muted-foreground">
                  Off = app-only policy, used only for per-app extra authentication (never for login).
                </p>
              </div>
              <Switch id="pol-login" checked={editor.appliesToLogin}
                      onCheckedChange={(v) => set({ appliesToLogin: v })} />
            </div>

            {!editor.appliesToLogin && (
              <div className="flex items-center justify-between gap-4 rounded-md border p-3">
                <div>
                  <Label htmlFor="pol-freshness">Re-authentication window (minutes)</Label>
                  <p className="text-xs text-muted-foreground">
                    When this policy is attached to an app, the user must complete a step-up on entry; it
                    stays valid this long before the app challenges again. Login alone never satisfies it.
                  </p>
                </div>
                <Input id="pol-freshness" type="number" min={1} className="w-24"
                       value={editor.stepUpFreshnessMinutes}
                       onChange={(e) => set({ stepUpFreshnessMinutes: e.target.value })} />
              </div>
            )}

            <div className="flex items-center justify-between rounded-md border p-3">
              <div>
                <Label htmlFor="pol-enroll">Allow enrollment at login</Label>
                <p className="text-xs text-muted-foreground">
                  On: a user missing a required factor sets it up (TOTP QR / passkey) during login.
                  Off: login only verifies factors the user already has (admin must pre-provision).
                </p>
              </div>
              <Switch id="pol-enroll" checked={editor.allowEnrollmentAtLogin}
                      onCheckedChange={(v) => set({ allowEnrollmentAtLogin: v })} />
            </div>

            {editor.appliesToLogin && (
              <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
                Leave roles &amp; users empty to apply this policy to <strong>everyone</strong> (global).
              </p>
            )}

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
              <div className="max-h-36 space-y-1 overflow-y-auto rounded-md border p-3">
                {users.length === 0 ? <span className="text-sm text-muted-foreground">none</span> : users.map((u) => (
                  <label key={u.id} className="flex items-center gap-2 text-sm">
                    <Checkbox checked={editor.userIds.includes(u.id)}
                              onCheckedChange={() => set({ userIds: toggle(editor.userIds, u.id) })} /> {u.username}
                  </label>
                ))}
              </div>
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
