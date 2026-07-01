import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { AppWindow, ArrowLeft, Lock } from "lucide-react";
import {
  getGroup, getGroupApplications, getGroupMembers,
  type Group, type GroupApp, type GroupMembersPage,
} from "@/groups";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const SIZE = 20;
type Tab = "members" | "apps";

export default function GroupDetail() {
  const { id = "" } = useParams();
  const [group, setGroup] = useState<Group | null>(null);
  const [tab, setTab] = useState<Tab>("members");
  const [members, setMembers] = useState<GroupMembersPage | null>(null);
  const [page, setPage] = useState(0);
  const [apps, setApps] = useState<GroupApp[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { getGroup(id).then(setGroup).catch((e) => setError(String(e))); }, [id]);
  useEffect(() => {
    if (tab === "members") getGroupMembers(id, page, SIZE).then(setMembers).catch((e) => setError(String(e)));
  }, [id, tab, page]);
  useEffect(() => {
    if (tab === "apps") getGroupApplications(id).then(setApps).catch((e) => setError(String(e)));
  }, [id, tab]);

  const lastPage = members ? Math.max(0, Math.ceil(members.total / SIZE) - 1) : 0;

  return (
    <>
      <Link to="/admin/groups" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> Back to groups
      </Link>
      <PageHeader
        title={group ? group.name : "Group"}
        description={group?.description || "Organizational group"}
        actions={group?.system ? <Badge variant="secondary"><Lock className="size-3" /> System group</Badge> : undefined}
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      <div className="mb-4 flex gap-1 border-b">
        {(["members", "apps"] as Tab[]).map((t) => (
          <button key={t} onClick={() => setTab(t)}
                  className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${tab === t ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"}`}>
            {t === "members" ? `Members${members ? ` (${members.total})` : ""}` : "Applications"}
          </button>
        ))}
      </div>

      {tab === "members" && (
        <>
          <Table>
            <TableHeader><TableRow><TableHead>Username</TableHead></TableRow></TableHeader>
            <TableBody>
              {members?.items.length === 0 ? (
                <TableRow><TableCell className="text-muted-foreground">No members.</TableCell></TableRow>
              ) : members?.items.map((m) => (
                <TableRow key={m.id}><TableCell className="font-medium">{m.label}</TableCell></TableRow>
              ))}
            </TableBody>
          </Table>
          {members && members.total > SIZE && (
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="text-muted-foreground">Page {page + 1} of {lastPage + 1}</span>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
                <Button variant="outline" size="sm" disabled={page >= lastPage} onClick={() => setPage((p) => p + 1)}>Next</Button>
              </div>
            </div>
          )}
        </>
      )}

      {tab === "apps" && (
        <Table>
          <TableHeader><TableRow><TableHead>Application</TableHead><TableHead>Type</TableHead></TableRow></TableHeader>
          <TableBody>
            {apps?.length === 0 ? (
              <TableRow><TableCell colSpan={2} className="text-muted-foreground">No applications assigned to this group.</TableCell></TableRow>
            ) : apps?.map((a) => (
              <TableRow key={`${a.type}:${a.id}`}>
                <TableCell className="font-medium"><span className="inline-flex items-center gap-2"><AppWindow className="size-4 text-muted-foreground" />{a.name}</span></TableCell>
                <TableCell><Badge variant="muted">{a.type}</Badge></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </>
  );
}
