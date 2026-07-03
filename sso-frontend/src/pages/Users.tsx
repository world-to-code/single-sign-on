import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { KeyRound, Plus } from "lucide-react";
import { createUser, type AdminUser } from "@/users";
import { errorMessage } from "@/api";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { tokens } from "@/lib/utils";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

const emptyForm = { username: "", email: "", displayName: "", password: "", roles: "ROLE_USER" };

export default function Users() {
  const { items: users, total, page, setPage, size, error, reload } = usePaginated<AdminUser>("/api/admin/users");
  const [formError, setFormError] = useState<string | null>(null);
  const [form, setForm] = useState({ ...emptyForm });
  const [open, setOpen] = useState(false);

  async function create(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    try {
      await createUser({
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
      setFormError(errorMessage(e));
    }
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
        {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}
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
        description={total ? `${total} user${total === 1 ? "" : "s"} in the directory.` : "Manage directory users."}
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
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className="font-medium">
                    <Link to={`/admin/users/${u.id}`} className="text-primary hover:underline">{u.username}</Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{u.email}</TableCell>
                  <TableCell>{u.displayName ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell><Badge variant={u.enabled ? "success" : "muted"}>{u.enabled ? "enabled" : "disabled"}</Badge></TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {u.roles.length ? u.roles.map((r) => <Badge key={r} variant="secondary">{r}</Badge>) : <span className="text-muted-foreground">—</span>}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />
    </>
  );
}
