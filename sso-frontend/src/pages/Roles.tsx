import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ShieldCheck, Lock, Pencil, Plus, Trash2 } from "lucide-react";
import {
  ADMIN_ROLE, createRole, deleteRole, listPermissions, listRoles, togglePermission, updateRole,
  type Permission, type Role,
} from "@/roles";
import { PageHeader } from "@/components/PageHeader";
import { PermissionPicker } from "@/components/PermissionPicker";
import { useEditorForm } from "@/hooks/useEditorForm";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { Field } from "@/components/form/fields";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

interface RoleEditor {
  id: string | null;
  name: string;
  permissions: string[];
  system: boolean;
}

const BLANK: RoleEditor = { id: null, name: "", permissions: [], system: false };

export default function Roles() {
  const { t } = useTranslation("states");
  const confirmDelete = useDeleteConfirm();
  const [roles, setRoles] = useState<Role[] | null>(null);
  const [catalog, setCatalog] = useState<Permission[]>([]);
  const [error, setError] = useState<string | null>(null);

  function reload() {
    listRoles().then(setRoles).catch((e) => setError(String(e)));
  }
  useEffect(() => {
    reload();
    listPermissions().then(setCatalog).catch(() => undefined);
  }, []);

  const form = useEditorForm<RoleEditor>({
    blank: BLANK,
    toRequest: (e) => ({ name: e.name, permissions: e.permissions }),
    create: (body) => createRole(body as { name: string; permissions: string[] }),
    update: (id, body) => updateRole(id, body as { name: string; permissions: string[] }),
    onSaved: reload,
  });

  const editing = form.editor.id !== null;
  const isAdmin = form.editor.name === ADMIN_ROLE;
  const nameLocked = editing && form.editor.system;
  const permsLocked = isAdmin;

  function toggle(perm: Permission) {
    form.set({ permissions: togglePermission(form.editor.permissions, perm, catalog) });
  }

  function remove(role: Role) {
    confirmDelete({
      title: "Delete role?",
      description: `${role.name} will be removed and unassigned from all users and groups.`,
      run: () => deleteRole(role.id),
      onDeleted: reload,
    });
  }

  const newButton = <Button onClick={form.openCreate}><Plus /> New role</Button>;

  return (
    <>
      <PageHeader
        title="Roles"
        description={roles ? `${roles.length} role${roles.length === 1 ? "" : "s"} — bundle permissions into a role, then assign it to users or groups.` : "Compose permissions into roles."}
        actions={newButton}
      />

      <DataList
        data={roles}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<ShieldCheck className="size-8" />} title={t("rolesEmptyTitle")} hint={t("rolesEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Role</TableHead>
                <TableHead>Permissions</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((role) => (
                <TableRow key={role.id}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <Link to={`/admin/roles/${role.id}`} className="text-primary hover:underline">{role.name}</Link>
                      {role.system && <Badge variant="secondary"><Lock className="size-3" /> System</Badge>}
                    </span>
                  </TableCell>
                  <TableCell>
                    {role.name === ADMIN_ROLE ? (
                      <span className="text-muted-foreground">All permissions (auto-managed)</span>
                    ) : role.permissions.length ? (
                      <div className="flex flex-wrap gap-1">
                        {role.permissions.map((p) => <Badge key={p} variant="outline" className="font-mono text-xs">{p}</Badge>)}
                      </div>
                    ) : (
                      <span className="text-muted-foreground">—</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost" size="icon"
                        title={role.name === ADMIN_ROLE ? "Managed automatically" : "Edit role"}
                        disabled={role.name === ADMIN_ROLE}
                        onClick={() => form.openEdit({ id: role.id, name: role.name, permissions: role.permissions, system: role.system })}
                      >
                        <Pencil />
                      </Button>
                      <Button
                        variant="ghost" size="icon"
                        title={role.system ? "System roles cannot be deleted" : "Delete role"}
                        className="text-muted-foreground hover:text-destructive"
                        disabled={role.system}
                        onClick={() => remove(role)}
                      >
                        <Trash2 />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      <Dialog open={form.open} onOpenChange={form.setOpen}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{editing ? "Edit role" : "Create role"}</DialogTitle>
            <DialogDescription>
              {isAdmin
                ? "ROLE_ADMIN is managed automatically and always holds every permission."
                : "Choose the permissions this role grants. Assign the role to users or groups afterwards."}
            </DialogDescription>
          </DialogHeader>
          {form.error && <Alert variant="destructive"><AlertDescription>{form.error}</AlertDescription></Alert>}
          <form onSubmit={form.save} className="space-y-4">
            <Field label="Name" hint={nameLocked ? "System roles cannot be renamed." : undefined}>
              <Input
                value={form.editor.name}
                onChange={(e) => form.set({ name: e.target.value })}
                placeholder="e.g. APP_MANAGER"
                disabled={nameLocked || isAdmin}
                required
              />
            </Field>
            <PermissionPicker catalog={catalog} selected={form.editor.permissions} onToggle={toggle} disabled={permsLocked} />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => form.setOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={isAdmin}>{editing ? "Save role" : "Create role"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
