import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, Lock, Trash2, UserPlus, Users } from "lucide-react";
import { listRoleMembers, listRoles, addRoleMember, removeRoleMember, type Role, type RoleMember } from "@/roles";
import { searchUsers } from "@/groups";
import { errorMessage } from "@/api";
import { PageHeader } from "@/components/PageHeader";
import { SearchSelect } from "@/components/SearchSelect";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export default function RoleDetail() {
  const { id = "" } = useParams();
  const [role, setRole] = useState<Role | null>(null);
  const [members, setMembers] = useState<RoleMember[] | null>(null);
  const [addKey, setAddKey] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    listRoles().then((roles) => setRole(roles.find((r) => r.id === id) ?? null)).catch((e) => setError(errorMessage(e)));
    listRoleMembers(id).then(setMembers).catch((e) => setError(errorMessage(e)));
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

  return (
    <>
      <Link to="/admin/roles" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> Back to roles
      </Link>
      <PageHeader
        title={role ? role.name : "Role"}
        description="Users who hold this role directly. Group-delegated membership is managed on each group."
        actions={role?.system ? <Badge variant="secondary"><Lock className="size-3" /> System role</Badge> : undefined}
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {role && (
        <div className="mb-6">
          <p className="mb-2 text-sm font-medium text-muted-foreground">Permissions</p>
          {role.permissions.length === 0 ? (
            <p className="text-sm text-muted-foreground">No permissions bundled in this role.</p>
          ) : (
            <div className="flex flex-wrap gap-1">
              {role.permissions.map((p) => <Badge key={p} variant="outline" className="font-mono text-xs">{p}</Badge>)}
            </div>
          )}
        </div>
      )}

      <div className="mb-3 flex items-center gap-2 text-sm font-medium">
        <Users className="size-4 text-muted-foreground" />
        Members{members ? ` (${members.length})` : ""}
      </div>

      <div className="mb-4 flex items-center gap-2">
        <UserPlus className="size-4 text-muted-foreground" />
        <div className="flex-1">
          <SearchSelect
            resetKey={addKey}
            placeholder="Search users by name to grant this role…"
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
            <TableHead>User</TableHead>
            <TableHead>Status</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {members === null ? (
            <TableRow><TableCell colSpan={3} className="text-muted-foreground">Loading…</TableCell></TableRow>
          ) : members.length === 0 ? (
            <TableRow><TableCell colSpan={3} className="text-muted-foreground">No users hold this role directly.</TableCell></TableRow>
          ) : members.map((m) => (
            <TableRow key={m.id}>
              <TableCell className="font-medium">
                <Link to={`/admin/users/${m.id}`} className="text-primary hover:underline">{m.username}</Link>
                {m.displayName && m.displayName !== m.username && <span className="ml-2 text-muted-foreground">{m.displayName}</span>}
              </TableCell>
              <TableCell>
                {m.enabled ? <Badge variant="muted">Enabled</Badge> : <Badge variant="secondary">Disabled</Badge>}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost" size="icon"
                  title="Revoke role from user"
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
