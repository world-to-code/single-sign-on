import { useEffect, useState } from "react";
import { ChevronRight, Pencil, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut } from "../api";
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
  stepsText: string;
  roleIds: string[];
  userIds: string[];
}

const blankEditor: Editor = { id: null, name: "", priority: "10", enabled: true, appliesToLogin: true, allowEnrollmentAtLogin: true, stepsText: "PASSWORD; TOTP, EMAIL", roleIds: [], userIds: [] };

const stepsToText = (steps: string[][]) => steps.map((s) => s.join(", ")).join("; ");
const textToSteps = (text: string) =>
  text.split(";").map((s) => s.split(",").map((f) => f.trim().toUpperCase()).filter(Boolean)).filter((s) => s.length > 0);

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
      steps: textToSteps(e.stepsText),
      assignedRoleIds: e.roleIds,
      assignedUserIds: e.userIds,
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
      stepsText: stepsToText(p.steps), roleIds: [...p.assignedRoleIds], userIds: [...p.assignedUserIds],
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

            <div className="space-y-2">
              <Label htmlFor="pol-steps">Steps</Label>
              <Input id="pol-steps" value={editor.stepsText} placeholder="PASSWORD; TOTP, EMAIL"
                     onChange={(e) => set({ stepsText: e.target.value })} />
              <p className="text-xs text-muted-foreground">
                <code>;</code> between steps, <code>,</code> for a choice within a step.
              </p>
            </div>

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
