import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  ArrowLeft, KeyRound, Power, PowerOff, RotateCcw, ShieldCheck, Trash2,
} from "lucide-react";
import type { SessionView } from "@/auth";
import {
  getUser, resetUserMfa, setUserEnabled, setUserPermissions, updateUser, type UserDetail as UserDetailData,
} from "@/users";
import { ADMIN_ROLE, listPermissions, listRoles, togglePermission, type Permission, type Role } from "@/roles";
import { PageHeader } from "@/components/PageHeader";
import { PermissionPicker } from "@/components/PermissionPicker";
import { useConfirm } from "@/components/ConfirmProvider";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";

const PLACEHOLDERS = [
  { title: "Provisioning (SCIM)", hint: "Source system and external identity for provisioned users." },
  { title: "Custom attributes", hint: "Directory attributes and profile metadata." },
  { title: "Assigned applications", hint: "Applications this user can launch from the portal." },
  { title: "Sessions & activity", hint: "Active sessions and recent sign-in activity." },
  { title: "Authentication devices", hint: "Enrolled factors and passkey management." },
];

export default function UserDetail({ session }: { session: SessionView }) {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const confirmDelete = useDeleteConfirm();
  const [user, setUser] = useState<UserDetailData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [allRoles, setAllRoles] = useState<Role[]>([]);
  const [catalog, setCatalog] = useState<Permission[]>([]);
  const [rolesOpen, setRolesOpen] = useState(false);
  const [roleSel, setRoleSel] = useState<string[]>([]);
  const [permsOpen, setPermsOpen] = useState(false);
  const [permSel, setPermSel] = useState<string[]>([]);

  function load() { getUser(id).then(setUser).catch((e) => setError(String(e))); }
  useEffect(load, [id]);
  useEffect(() => {
    listRoles().then(setAllRoles).catch(() => undefined);
    listPermissions().then(setCatalog).catch(() => undefined);
  }, []);

  const isSelf = !!user && user.username === session.username;
  // Can't revoke your own directly-held admin role (enforced server-side; reflected in the UI).
  const selfLockedAdmin = isSelf && !!user && user.roleAssignments.some((a) => a.direct && a.roleName === ADMIN_ROLE);

  async function toggleEnabled() {
    if (!user) return;
    await setUserEnabled(id, !user.enabled);
    load();
  }

  async function resetMfa() {
    if (!user) return;
    if (await confirm({ title: "Reset MFA?", description: `${user.username} will need to re-enroll their authenticator (TOTP) on next sign-in.`, confirmText: "Reset MFA" })) {
      await resetUserMfa(id);
      load();
    }
  }

  function remove() {
    if (!user) return;
    confirmDelete({
      title: "Delete user?",
      description: `${user.username} will be permanently removed from the directory.`,
      path: `/api/admin/users/${id}`,
      onDeleted: () => navigate("/admin/users"),
    });
  }

  function openRoles() {
    setRoleSel(user ? user.roleAssignments.filter((a) => a.direct).map((a) => a.roleName) : []);
    setRolesOpen(true);
  }
  function toggleRole(name: string) {
    if (selfLockedAdmin && name === ADMIN_ROLE) return; // cannot revoke your own admin role
    setRoleSel((sel) => (sel.includes(name) ? sel.filter((r) => r !== name) : [...sel, name]));
  }
  async function saveRoles() {
    if (!user) return;
    try {
      await updateUser(id, { displayName: user.displayName, email: user.email, enabled: user.enabled, roles: roleSel });
      setRolesOpen(false);
      load();
    } catch (e) {
      setError(String(e));
    }
  }

  function openPerms() {
    setPermSel(user ? [...user.directPermissions] : []);
    setPermsOpen(true);
  }
  async function savePerms() {
    try {
      await setUserPermissions(id, permSel);
      setPermsOpen(false);
      load();
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <>
      <Link to="/admin/users" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> Back to users
      </Link>
      <PageHeader
        title={user ? (user.displayName || user.username) : "User"}
        description={user ? user.email : "Directory user"}
        actions={user && (
          <div className="flex items-center gap-1">
            <Button
              variant="outline" size="sm" onClick={toggleEnabled}
              disabled={isSelf && user.enabled}
              title={isSelf && user.enabled ? "You cannot disable your own account" : undefined}
            >
              {user.enabled ? <PowerOff className="size-4" /> : <Power className="size-4" />}
              {user.enabled ? "Disable" : "Enable"}
            </Button>
            <Button variant="outline" size="sm" onClick={resetMfa}><RotateCcw className="size-4" /> Reset MFA</Button>
            <Button
              variant="outline" size="sm" className="text-destructive hover:text-destructive" onClick={remove}
              disabled={isSelf}
              title={isSelf ? "You cannot delete your own account" : undefined}
            >
              <Trash2 className="size-4" /> Delete
            </Button>
          </div>
        )}
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {user && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>Profile</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 sm:grid-cols-2">
              <Detail label="Username" value={user.username} mono />
              <Detail label="Email" value={user.email} />
              <Detail label="Display name" value={user.displayName || "—"} />
              <Detail label="External ID" value={user.externalId || "—"} />
              <div className="space-y-1.5">
                <p className="text-xs font-medium text-muted-foreground">Status</p>
                <div className="flex flex-wrap gap-1">
                  <Badge variant={user.enabled ? "success" : "muted"}>{user.enabled ? "enabled" : "disabled"}</Badge>
                  <Badge variant={user.emailVerified ? "success" : "muted"}>{user.emailVerified ? "email verified" : "email unverified"}</Badge>
                  {!user.accountNonLocked && <Badge variant="destructive">locked</Badge>}
                </div>
              </div>
              <Detail label="Created" value={new Date(user.createdAt).toLocaleString()} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <div>
                <CardTitle>Roles</CardTitle>
                <CardDescription>Direct assignments and roles inherited through groups.</CardDescription>
              </div>
              <Button variant="outline" size="sm" onClick={openRoles}><ShieldCheck className="size-4" /> Edit direct roles</Button>
            </CardHeader>
            <CardContent>
              {user.roleAssignments.length === 0 ? (
                <p className="text-sm text-muted-foreground">No roles assigned.</p>
              ) : (
                <div className="space-y-2">
                  {user.roleAssignments.map((a) => (
                    <div key={a.roleId} className="flex items-center justify-between gap-3 rounded-lg border p-3">
                      <span className="font-medium">{a.roleName}</span>
                      <div className="flex flex-wrap justify-end gap-1">
                        {a.direct && <Badge variant="default">Direct</Badge>}
                        {a.viaGroups.map((g) => <Badge key={g} variant="secondary">via {g}</Badge>)}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <div>
                <CardTitle>Direct permissions</CardTitle>
                <CardDescription>Permissions granted to this user in addition to their roles.</CardDescription>
              </div>
              <Button variant="outline" size="sm" onClick={openPerms}><KeyRound className="size-4" /> Edit</Button>
            </CardHeader>
            <CardContent>
              {user.directPermissions.length === 0 ? (
                <p className="text-sm text-muted-foreground">No direct permissions.</p>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {user.directPermissions.map((p) => <Badge key={p} variant="outline" className="font-mono text-xs">{p}</Badge>)}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Effective permissions</CardTitle>
              <CardDescription>All permissions in effect — from roles, groups and direct grants (read-implication expanded).</CardDescription>
            </CardHeader>
            <CardContent>
              {user.effectivePermissions.length === 0 ? (
                <p className="text-sm text-muted-foreground">None.</p>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {user.effectivePermissions.map((p) => <Badge key={p} variant="muted" className="font-mono text-xs">{p}</Badge>)}
                </div>
              )}
            </CardContent>
          </Card>

          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {PLACEHOLDERS.map((card) => (
              <Card key={card.title} className="opacity-60">
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">{card.title}</CardTitle>
                  <CardDescription>{card.hint}</CardDescription>
                </CardHeader>
                <CardContent><Badge variant="muted">Coming soon</Badge></CardContent>
              </Card>
            ))}
          </div>
        </div>
      )}

      <Dialog open={rolesOpen} onOpenChange={setRolesOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Direct roles</DialogTitle>
            <DialogDescription>
              Assign roles directly to <strong>{user?.username}</strong>. Group-inherited roles are managed on the group.
            </DialogDescription>
          </DialogHeader>
          {allRoles.length === 0 ? (
            <p className="text-sm text-muted-foreground">No roles available.</p>
          ) : (
            <div className="grid max-h-72 grid-cols-1 gap-1 overflow-y-auto sm:grid-cols-2">
              {allRoles.map((role) => {
                const locked = selfLockedAdmin && role.name === ADMIN_ROLE;
                return (
                  <label key={role.id} className={`flex items-center gap-2.5 rounded-md border p-2.5 text-sm transition-colors has-[:checked]:border-primary has-[:checked]:bg-accent ${locked ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:bg-muted/60"}`}>
                    <Checkbox className="size-4" checked={roleSel.includes(role.name)} disabled={locked} onCheckedChange={() => toggleRole(role.name)} />
                    <span>{role.name}</span>
                  </label>
                );
              })}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setRolesOpen(false)}>Cancel</Button>
            <Button onClick={saveRoles}>Save roles</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={permsOpen} onOpenChange={setPermsOpen}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Direct permissions</DialogTitle>
            <DialogDescription>Grant fine-grained permissions to <strong>{user?.username}</strong>.</DialogDescription>
          </DialogHeader>
          <PermissionPicker catalog={catalog} selected={permSel} onToggle={(perm) => setPermSel((sel) => togglePermission(sel, perm, catalog))} />
          <DialogFooter>
            <Button variant="outline" onClick={() => setPermsOpen(false)}>Cancel</Button>
            <Button onClick={savePerms}>Save permissions</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Detail({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      <p className={`text-sm ${mono ? "font-mono" : ""}`}>{value}</p>
    </div>
  );
}
