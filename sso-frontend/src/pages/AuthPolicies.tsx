import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronRight, Pencil, Plus, Trash2 } from "lucide-react";
import { apiGet, type Page } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { usersByIds } from "@/groups";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";

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

export default function AuthPolicies() {
  const confirmDelete = useDeleteConfirm();
  const [policies, setPolicies] = useState<Policy[] | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [userNames, setUserNames] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  function reload() {
    apiGet<Page<Policy>>("/api/admin/auth-policies?size=100")
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

  async function remove(p: Policy) {
    await confirmDelete({
      title: "Delete policy?",
      description: `"${p.name}" will be permanently removed.`,
      path: `/api/admin/auth-policies/${p.id}`,
      onDeleted: reload,
    });
  }

  const roleName = (id: string) => roles.find((r) => r.id === id)?.name ?? id;
  const userName = (id: string) => userNames[id] ?? id;

  return (
    <>
      <PageHeader
        title="Authentication Policies"
        description="Factor chains applied per role or user — the highest-priority matching policy wins."
        actions={<Button asChild><Link to="/admin/auth-policies/new"><Plus /> New policy</Link></Button>}
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
                        <Button variant="ghost" size="icon" asChild><Link to={`/admin/auth-policies/${p.id}`}><Pencil /></Link></Button>
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
    </>
  );
}
