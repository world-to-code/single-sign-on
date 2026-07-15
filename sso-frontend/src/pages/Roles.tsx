import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router-dom";
import { ShieldCheck, Lock, Pencil, Plus, Trash2 } from "lucide-react";
import { ADMIN_ROLE, createRole, deleteRole, listRoles, type Role } from "@/roles";
import { errorMessage } from "@/api";
import { PageHeader } from "@/components/PageHeader";
import { TagList } from "@/components/TagList";
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

export default function Roles() {
  const { t } = useTranslation(["console", "states"]);
  const navigate = useNavigate();
  const confirmDelete = useDeleteConfirm();
  const [roles, setRoles] = useState<Role[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  function reload() {
    listRoles().then(setRoles).catch((e) => setError(String(e)));
  }
  useEffect(reload, []);

  // Name-first: create an empty role, then land on its detail to assign permissions and inheritance.
  async function submitCreate(e: React.FormEvent) {
    e.preventDefault();
    setCreating(true);
    setCreateError(null);
    try {
      const role = await createRole({ name: name.trim(), permissions: [] });
      setCreateOpen(false);
      navigate(`/admin/roles/${role.id}`);
    } catch (err) {
      setCreateError(errorMessage(err));
    } finally {
      setCreating(false);
    }
  }

  function openCreate() {
    setName("");
    setCreateError(null);
    setCreateOpen(true);
  }

  function remove(role: Role) {
    confirmDelete({
      title: t("rolesDeleteTitle"),
      description: t("rolesDeleteDescription", { name: role.name }),
      run: () => deleteRole(role.id),
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title={t("rolesTitle")}
        description={roles ? t("rolesCount", { count: roles.length }) : t("rolesDescription")}
        actions={<Button onClick={openCreate}><Plus /> {t("rolesNew")}</Button>}
      />

      <DataList
        data={roles}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<ShieldCheck className="size-8" />} title={t("states:rolesEmptyTitle")} hint={t("states:rolesEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("rolesColRole")}</TableHead>
                <TableHead>{t("rolesColPermissions")}</TableHead>
                <TableHead className="text-right">{t("rolesColActions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((role) => (
                <TableRow key={role.id}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <Link to={`/admin/roles/${role.id}`} className="text-primary hover:underline">{role.name}</Link>
                      {role.system && <Badge variant="secondary"><Lock className="size-3" /> {t("badgeSystem")}</Badge>}
                    </span>
                  </TableCell>
                  <TableCell>
                    {role.name === ADMIN_ROLE ? (
                      <span className="text-muted-foreground">{t("rolesAllPermissions")}</span>
                    ) : (
                      <TagList items={role.permissions} mono />
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost" size="icon"
                        title={role.name === ADMIN_ROLE ? t("rolesManagedAuto") : t("rolesEditRole")}
                        disabled={role.name === ADMIN_ROLE}
                        onClick={() => navigate(`/admin/roles/${role.id}`)}
                      >
                        <Pencil />
                      </Button>
                      <Button
                        variant="ghost" size="icon"
                        title={role.system ? t("rolesSystemNoDelete") : t("rolesDeleteRole")}
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

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("rolesDialogCreate")}</DialogTitle>
            <DialogDescription>{t("rolesCreateHint")}</DialogDescription>
          </DialogHeader>
          {createError && <Alert variant="destructive"><AlertDescription>{createError}</AlertDescription></Alert>}
          <form onSubmit={submitCreate} className="space-y-4">
            <Field label={t("rolesNameLabel")}>
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("rolesNamePlaceholder")} required autoFocus />
            </Field>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setCreateOpen(false)}>{t("cancel")}</Button>
              <Button type="submit" disabled={creating || name.trim() === ""}>{t("rolesCreateRole")}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
