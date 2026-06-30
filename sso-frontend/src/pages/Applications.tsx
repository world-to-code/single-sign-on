import { useEffect, useState } from "react";
import { AppWindow, Network, Trash2, UserPlus, Users as UsersIcon } from "lucide-react";
import { apiGet, apiPost } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

interface Application { id: string; type: "OIDC" | "SAML"; name: string; launchUrl: string | null; }
interface Assignment { id: string; subjectType: string; subjectName: string; requiredPolicyId: string | null; }
interface Role { id: string; name: string; }
interface User { id: string; username: string; }
interface Policy { id: string; name: string; }

export default function Applications() {
  const confirmDelete = useDeleteConfirm();
  const [apps, setApps] = useState<Application[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [policies, setPolicies] = useState<Policy[]>([]);

  const [active, setActive] = useState<Application | null>(null);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [subjectType, setSubjectType] = useState<"USER" | "ROLE">("ROLE");
  const [subjectId, setSubjectId] = useState("");
  const [requiredPolicyId, setRequiredPolicyId] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    apiGet<Application[]>("/api/admin/applications").then(setApps).catch((e) => setError(String(e)));
    apiGet<Role[]>("/api/admin/roles").then(setRoles).catch(() => undefined);
    apiGet<User[]>("/api/admin/users").then(setUsers).catch(() => undefined);
    apiGet<Policy[]>("/api/admin/auth-policies").then(setPolicies).catch(() => undefined);
  }, []);

  const policyName = (id: string | null) => (id ? policies.find((p) => p.id === id)?.name ?? "policy" : null);

  function loadAssignments(app: Application) {
    apiGet<Assignment[]>(`/api/admin/applications/${app.type}/${app.id}/assignments`).then(setAssignments).catch(() => setAssignments([]));
  }
  function manage(app: Application) {
    setFormError(null); setActive(app); setSubjectType("ROLE"); setSubjectId(""); setRequiredPolicyId(""); setAssignments([]);
    loadAssignments(app);
  }

  async function add() {
    if (!active || !subjectId) return;
    setFormError(null);
    try {
      await apiPost("/api/admin/applications/assignments", {
        appType: active.type, appId: active.id, subjectType, subjectId,
        requiredPolicyId: requiredPolicyId || null,
      });
      setSubjectId(""); setRequiredPolicyId("");
      loadAssignments(active);
    } catch (e) {
      setFormError(String(e));
    }
  }

  async function removeAssignment(a: Assignment) {
    await confirmDelete({
      title: "Remove access?",
      description: `${a.subjectName} will lose access to ${active?.name}.`,
      confirmText: "Remove",
      path: `/api/admin/applications/assignments/${a.id}`,
      onDeleted: () => { if (active) loadAssignments(active); },
    });
  }

  const options = subjectType === "ROLE"
    ? roles.map((r) => ({ id: r.id, label: r.name }))
    : users.map((u) => ({ id: u.id, label: u.username }));

  return (
    <>
      <PageHeader title="Applications" description="Assign OIDC and SAML applications to users and groups for the user portal." />

      <DataList
        data={apps}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<AppWindow className="size-8" />} title="No applications" hint="Register an OIDC client or SAML provider first." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow><TableHead>Application</TableHead><TableHead>Type</TableHead><TableHead>Launch URL</TableHead><TableHead className="w-0" /></TableRow>
            </TableHeader>
            <TableBody>
              {items.map((app) => (
                <TableRow key={`${app.type}:${app.id}`}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      {app.type === "SAML" ? <Network className="size-4 text-muted-foreground" /> : <AppWindow className="size-4 text-muted-foreground" />}
                      {app.name}
                    </span>
                  </TableCell>
                  <TableCell><Badge variant="muted">{app.type}</Badge></TableCell>
                  <TableCell className="max-w-xs truncate font-mono text-xs text-muted-foreground">{app.launchUrl ?? "—"}</TableCell>
                  <TableCell className="text-right">
                    <Button variant="outline" size="sm" onClick={() => manage(app)}><UsersIcon /> Manage access</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      <Dialog open={!!active} onOpenChange={(o) => { if (!o) setActive(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Manage access — {active?.name}</DialogTitle>
            <DialogDescription>Users and groups assigned here can launch this app from their portal.</DialogDescription>
          </DialogHeader>

          {formError && <Alert variant="destructive"><AlertDescription>{formError}</AlertDescription></Alert>}

          <div className="rounded-md border">
            {assignments.length === 0 ? (
              <p className="p-4 text-sm text-muted-foreground">No assignments yet.</p>
            ) : assignments.map((a) => (
              <div key={a.id} className="flex items-center justify-between border-b p-3 last:border-0">
                <span className="flex flex-wrap items-center gap-2 text-sm">
                  <Badge variant={a.subjectType === "ROLE" ? "secondary" : "outline"}>{a.subjectType === "ROLE" ? "Group" : "User"}</Badge>
                  {a.subjectName}
                  {a.requiredPolicyId && <Badge variant="default">+ {policyName(a.requiredPolicyId)}</Badge>}
                </span>
                <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => removeAssignment(a)}><Trash2 /></Button>
              </div>
            ))}
          </div>

          <div className="space-y-2">
            <Label>Assign to</Label>
            <div className="flex gap-2">
              <Select value={subjectType} onChange={(e) => { setSubjectType(e.target.value as "USER" | "ROLE"); setSubjectId(""); }} className="w-32">
                <option value="ROLE">Group</option><option value="USER">User</option>
              </Select>
              <Select value={subjectId} onChange={(e) => setSubjectId(e.target.value)} className="flex-1">
                <option value="">Select…</option>
                {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
              </Select>
            </div>
            <Label>Extra authentication <span className="text-muted-foreground">(optional)</span></Label>
            <div className="flex gap-2">
              <Select value={requiredPolicyId} onChange={(e) => setRequiredPolicyId(e.target.value)} className="flex-1">
                <option value="">No extra authentication</option>
                {policies.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </Select>
              <Button onClick={add} disabled={!subjectId}><UserPlus /> Add</Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Selecting a policy requires the assigned users to complete its factors (e.g. an extra passkey/TOTP) when launching this app.
            </p>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
