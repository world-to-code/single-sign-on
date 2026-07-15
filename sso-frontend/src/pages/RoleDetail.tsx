import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, Check, GitBranch, Lock, Pencil, Trash2, UserPlus, Users, X } from "lucide-react";
import {
  getRoleDetail, listPermissions, listRoleMembers, listRoles, setRoleInheritance, togglePermission,
  updateRole, addRoleMember, removeRoleMember,
  type Permission, type Role, type RoleDetail, type RoleMember,
} from "@/roles";
import { searchUsers } from "@/groups";
import { errorMessage } from "@/api";
import { PageHeader } from "@/components/PageHeader";
import { PermissionPicker } from "@/components/PermissionPicker";
import { SearchSelect } from "@/components/SearchSelect";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export default function RoleDetail() {
  const { t } = useTranslation("console");
  const { id = "" } = useParams();
  const [role, setRole] = useState<RoleDetail | null>(null);
  const [members, setMembers] = useState<RoleMember[] | null>(null);
  const [allRoles, setAllRoles] = useState<Role[]>([]);
  const [catalog, setCatalog] = useState<Permission[]>([]);
  const [addKey, setAddKey] = useState(0);
  const [inheritKey, setInheritKey] = useState(0);
  const [error, setError] = useState<string | null>(null);

  // Edit mode: permissions + inheritance are staged as drafts and committed together on Save.
  const [editing, setEditing] = useState(false);
  const [draftPerms, setDraftPerms] = useState<string[]>([]);
  const [draftInherits, setDraftInherits] = useState<{ id: string; name: string }[]>([]);
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    getRoleDetail(id).then(setRole).catch((e) => setError(errorMessage(e)));
    listRoleMembers(id).then(setMembers).catch((e) => setError(errorMessage(e)));
    listRoles().then(setAllRoles).catch(() => undefined);
    listPermissions().then(setCatalog).catch(() => undefined);
  }, [id]);
  useEffect(load, [load]);

  // Report success so the picker only clears when the grant/revoke actually went through (not on a rejection).
  async function run(op: () => Promise<unknown>): Promise<boolean> {
    try {
      await op();
      await listRoleMembers(id).then(setMembers);
      setError(null);
      return true;
    } catch (e) {
      setError(errorMessage(e));
      return false;
    }
  }

  function startEdit() {
    if (!role) return;
    setDraftPerms(role.permissions);
    setDraftInherits(role.inheritsFrom);
    setError(null);
    setEditing(true);
  }

  function toggleDraftPerm(perm: Permission) {
    setDraftPerms((prev) => togglePermission(prev, perm, catalog));
  }

  async function saveEdits() {
    if (!role) return;
    setSaving(true);
    try {
      await updateRole(id, { name: role.name, permissions: draftPerms });
      const fresh = await setRoleInheritance(id, draftInherits.map((r) => r.id));
      setRole(fresh);
      setEditing(false);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  }

  const directPermissions = new Set(role?.permissions);
  const draftInheritedIds = new Set(draftInherits.map((r) => r.id));
  // In-tier roles this role could inherit: not itself and not already staged. Search is client-side over the
  // already-loaded list, reusing the same typeahead as user search.
  const inheritFetcher = (q: string) =>
    Promise.resolve(
      allRoles
        .filter((r) => r.id !== id && !draftInheritedIds.has(r.id) && r.name.toLowerCase().includes(q.trim().toLowerCase()))
        .map((r) => ({ id: r.id, label: r.name })),
    );
  const editable = role !== null && !role.system;

  const headerActions = role?.system ? (
    <Badge variant="secondary"><Lock className="size-3" /> {t("roleDetailSystemRole")}</Badge>
  ) : editing ? (
    <div className="flex items-center gap-2">
      <Button variant="outline" onClick={() => setEditing(false)} disabled={saving}>{t("cancel")}</Button>
      <Button onClick={() => void saveEdits()} disabled={saving}><Check /> {t("save")}</Button>
    </div>
  ) : editable ? (
    <Button variant="outline" onClick={startEdit}><Pencil /> {t("roleDetailEdit")}</Button>
  ) : undefined;

  return (
    <>
      <Link to="/admin/roles" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t("roleDetailBack")}
      </Link>
      <PageHeader
        title={role ? role.name : t("roleDetailFallbackTitle")}
        description={t("roleDetailDescription")}
        actions={headerActions}
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {role && (
        <div className="mb-6">
          <p className="mb-2 text-sm font-medium text-muted-foreground">{t("roleDetailPermissions")}</p>
          {editing ? (
            <PermissionPicker catalog={catalog} selected={draftPerms} onToggle={toggleDraftPerm} />
          ) : role.permissions.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("roleDetailNoPermissions")}</p>
          ) : (
            <div className="flex flex-wrap gap-1">
              {role.permissions.map((p) => <Badge key={p} variant="outline" className="font-mono text-xs">{p}</Badge>)}
            </div>
          )}
        </div>
      )}

      {role && (
        <div className="mb-6">
          <div className="mb-2 flex items-center gap-2 text-sm font-medium text-muted-foreground">
            <GitBranch className="size-4" /> {t("roleDetailInheritance")}
          </div>
          <p className="mb-2 text-xs text-muted-foreground">{t("roleDetailInheritanceHint")}</p>

          {(editing ? draftInherits : role.inheritsFrom).length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("roleDetailInheritsNone")}</p>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {(editing ? draftInherits : role.inheritsFrom).map((r) => (
                <span key={r.id} className="inline-flex items-center gap-1 rounded-full bg-primary/10 py-0.5 pl-2.5 pr-1 text-xs">
                  {r.name}
                  {editing && (
                    <button
                      type="button"
                      aria-label={t("roleDetailInheritRemove", { name: r.name })}
                      className="rounded-full p-0.5 text-muted-foreground hover:text-destructive"
                      onClick={() => setDraftInherits((prev) => prev.filter((x) => x.id !== r.id))}
                    >
                      <X className="size-3" />
                    </button>
                  )}
                </span>
              ))}
            </div>
          )}

          {editing && (
            <div className="mt-2 flex max-w-xs items-center gap-2">
              <SearchSelect
                resetKey={inheritKey}
                placeholder={t("roleDetailInheritAdd")}
                fetcher={inheritFetcher}
                onSelect={(s) => {
                  if (s) {
                    setDraftInherits((prev) => [...prev, { id: s.id, name: s.label }]);
                    setInheritKey((k) => k + 1);
                  }
                }}
              />
            </div>
          )}

          {!editing && role.effectivePermissions.length > role.permissions.length && (
            <div className="mt-3">
              <p className="mb-1 text-xs font-medium text-muted-foreground">{t("roleDetailEffectivePermissions")}</p>
              <div className="flex flex-wrap gap-1">
                {role.effectivePermissions.map((p) => (
                  <Badge
                    key={p}
                    variant={directPermissions.has(p) ? "outline" : "muted"}
                    className="font-mono text-xs"
                    title={directPermissions.has(p) ? undefined : t("roleDetailPermInherited")}
                  >
                    {p}
                  </Badge>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      <div className="mb-3 flex items-center gap-2 text-sm font-medium">
        <Users className="size-4 text-muted-foreground" />
        {members ? t("roleDetailMembersCount", { count: members.length }) : t("roleDetailMembers")}
      </div>

      <div className="mb-4 flex items-center gap-2">
        <UserPlus className="size-4 text-muted-foreground" />
        <div className="flex-1">
          <SearchSelect
            resetKey={addKey}
            placeholder={t("roleDetailSearchPlaceholder")}
            fetcher={searchUsers}
            onSelect={(s) => {
              if (s) void run(() => addRoleMember(id, s.id)).then((ok) => { if (ok) setAddKey((k) => k + 1); });
            }}
          />
        </div>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t("roleDetailColUser")}</TableHead>
            <TableHead>{t("roleDetailColStatus")}</TableHead>
            <TableHead className="text-right">{t("roleDetailColActions")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {members === null ? (
            <TableRow><TableCell colSpan={3} className="text-muted-foreground">{t("loading")}</TableCell></TableRow>
          ) : members.length === 0 ? (
            <TableRow><TableCell colSpan={3} className="text-muted-foreground">{t("roleDetailNoMembers")}</TableCell></TableRow>
          ) : members.map((m) => (
            <TableRow key={m.id}>
              <TableCell className="font-medium">
                <Link to={`/admin/users/${m.id}`} className="text-primary hover:underline">{m.username}</Link>
                {m.displayName && m.displayName !== m.username && <span className="ml-2 text-muted-foreground">{m.displayName}</span>}
              </TableCell>
              <TableCell>
                {m.enabled ? <Badge variant="muted">{t("badgeEnabled")}</Badge> : <Badge variant="secondary">{t("badgeDisabled")}</Badge>}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost" size="icon"
                  title={t("roleDetailRevoke")}
                  className="text-muted-foreground hover:text-destructive"
                  onClick={() => void run(() => removeRoleMember(id, m.id))}
                >
                  <Trash2 />
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </>
  );
}
