import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { KeyRound, Plus, Power, PowerOff, RotateCcw, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { useConfirm } from "@/components/ConfirmProvider";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { tokens } from "@/lib/utils";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

interface AdminUser {
  id: string;
  username: string;
  email: string;
  displayName: string | null;
  enabled: boolean;
  roles: string[];
  directPermissions: string[];
}

const emptyForm = { username: "", email: "", displayName: "", password: "", roles: "ROLE_USER" };

export default function Users() {
  const confirm = useConfirm();
  const confirmDelete = useDeleteConfirm();
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [catalog, setCatalog] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({ ...emptyForm });
  const [open, setOpen] = useState(false);
  const [permUser, setPermUser] = useState<AdminUser | null>(null);
  const [permSel, setPermSel] = useState<string[]>([]);

  function reload() {
    apiGet<AdminUser[]>("/api/admin/users").then(setUsers).catch((e) => setError(String(e)));
  }
  useEffect(() => {
    reload();
    apiGet<string[]>("/api/admin/permissions").then(setCatalog).catch(() => undefined);
  }, []);

  async function create(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await apiPost("/api/admin/users", {
        username: form.username,
        email: form.email,
        displayName: form.displayName || null,
        password: form.password,
        roles: tokens(form.roles, ","),
      });
      setForm({ ...emptyForm });
      setOpen(false);
      reload();
    } catch (e) {
      setError(String(e));
    }
  }

  async function setEnabled(user: AdminUser, enabled: boolean) {
    await apiPost(`/api/admin/users/${user.id}/enabled`, { enabled });
    reload();
  }

  async function remove(user: AdminUser) {
    await confirmDelete({
      title: "Delete user?",
      description: `${user.username} will be permanently removed from the directory.`,
      path: `/api/admin/users/${user.id}`,
      onDeleted: reload,
    });
  }

  async function resetMfa(user: AdminUser) {
    if (await confirm({ title: "Reset MFA?", description: `${user.username} will need to re-enroll their authenticator (TOTP) on next sign-in.`, confirmText: "Reset MFA" })) {
      await apiPost(`/api/admin/users/${user.id}/reset-mfa`);
      reload();
    }
  }

  function openPermissions(user: AdminUser) {
    setPermUser(user);
    setPermSel([...user.directPermissions]);
  }

  function togglePerm(p: string) {
    setPermSel((sel) => (sel.includes(p) ? sel.filter((x) => x !== p) : [...sel, p]));
  }

  async function savePermissions() {
    if (!permUser) return;
    await apiPut(`/api/admin/users/${permUser.id}/permissions`, { permissions: permSel });
    setPermUser(null);
    reload();
  }

  const createButton = (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button><Plus /> New user</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create user</DialogTitle>
          <DialogDescription>New users complete email verification + TOTP enrollment on first sign-in.</DialogDescription>
        </DialogHeader>
        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
        <form onSubmit={create} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="u-username">Username</Label>
            <Input id="u-username" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="u-email">Email</Label>
            <Input id="u-email" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="u-display">Display name <span className="text-muted-foreground">(optional)</span></Label>
            <Input id="u-display" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="u-password">Temporary password</Label>
            <Input id="u-password" type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="u-roles">Roles <span className="text-muted-foreground">(comma-separated)</span></Label>
            <Input id="u-roles" value={form.roles} onChange={(e) => setForm({ ...form, roles: e.target.value })} />
          </div>
          <DialogFooter>
            <Button type="submit"><Plus /> Create</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );

  return (
    <>
      <PageHeader
        title="Users"
        description={users ? `${users.length} user${users.length === 1 ? "" : "s"} in the directory.` : "Manage directory users."}
        actions={createButton}
      />

      <DataList
        data={users}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<KeyRound className="size-8" />} title="No users yet" hint="Create the first directory user." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Username</TableHead>
                <TableHead>Email</TableHead>
                <TableHead>Display</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Roles</TableHead>
                <TableHead>Direct permissions</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className="font-medium">{u.username}</TableCell>
                  <TableCell className="text-muted-foreground">{u.email}</TableCell>
                  <TableCell>{u.displayName ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell><Badge variant={u.enabled ? "success" : "muted"}>{u.enabled ? "enabled" : "disabled"}</Badge></TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {u.roles.length ? u.roles.map((r) => <Badge key={r} variant="secondary">{r}</Badge>) : <span className="text-muted-foreground">—</span>}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {u.directPermissions.length ? u.directPermissions.map((p) => <Badge key={p} variant="outline">{p}</Badge>) : <span className="text-muted-foreground">—</span>}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-1">
                      <Button variant="ghost" size="icon" title={u.enabled ? "Disable" : "Enable"} onClick={() => setEnabled(u, !u.enabled)}>
                        {u.enabled ? <PowerOff /> : <Power />}
                      </Button>
                      <Button variant="ghost" size="icon" title="Edit permissions" onClick={() => openPermissions(u)}><KeyRound /></Button>
                      <Button variant="ghost" size="icon" title="Reset MFA" onClick={() => resetMfa(u)}><RotateCcw /></Button>
                      <Button variant="ghost" size="icon" title="Delete" className="text-muted-foreground hover:text-destructive" onClick={() => remove(u)}><Trash2 /></Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      {/* Direct-permission editor */}
      <Dialog open={!!permUser} onOpenChange={(o) => { if (!o) setPermUser(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Direct permissions</DialogTitle>
            <DialogDescription>
              Grant fine-grained permissions to <strong>{permUser?.username}</strong> in addition to their roles.
            </DialogDescription>
          </DialogHeader>
          {catalog.length === 0 ? (
            <p className="text-sm text-muted-foreground">No permission catalog available.</p>
          ) : (
            <div className="grid max-h-72 grid-cols-1 gap-1 overflow-y-auto sm:grid-cols-2">
              {catalog.map((p) => {
                const checked = permSel.includes(p);
                return (
                  <label key={p} className="flex cursor-pointer items-center gap-2.5 rounded-md border p-2.5 text-sm transition-colors hover:bg-muted/60 has-[:checked]:border-primary has-[:checked]:bg-accent">
                    <Checkbox className="size-4" checked={checked} onCheckedChange={() => togglePerm(p)} />
                    <span className="font-mono text-xs">{p}</span>
                  </label>
                );
              })}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setPermUser(null)}>Cancel</Button>
            <Button onClick={savePermissions}>Save permissions</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
