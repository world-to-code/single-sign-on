import { useEffect, useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";
import { AppWindow, ArrowLeft, Lock, ShieldCheck } from "lucide-react";
import {
  getGroup, getGroupApplications, getGroupMembers, setGroupRoles,
  type Group, type GroupApp, type GroupMembersPage,
} from "@/groups";
import { errorMessage } from "@/api";
import { listRoles, type Role } from "@/roles";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { MetadataEditor } from "@/components/MetadataEditor";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const SIZE = 20;
type Tab = "members" | "roles" | "apps";

export default function GroupDetail() {
  const { t } = useTranslation("console");
  const { id = "" } = useParams();
  const [group, setGroup] = useState<Group | null>(null);
  const [tab, setTab] = useState<Tab>("members");
  const [members, setMembers] = useState<GroupMembersPage | null>(null);
  const [page, setPage] = useState(0);
  const [apps, setApps] = useState<GroupApp[] | null>(null);
  const [allRoles, setAllRoles] = useState<Role[]>([]);
  const [rolesOpen, setRolesOpen] = useState(false);
  const [roleSel, setRoleSel] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  function loadGroup() { getGroup(id).then(setGroup).catch((e) => setError(errorMessage(e))); }
  useEffect(loadGroup, [id]);
  useEffect(() => {
    if (tab === "members") getGroupMembers(id, page, SIZE).then(setMembers).catch((e) => setError(errorMessage(e)));
  }, [id, tab, page]);
  useEffect(() => {
    if (tab === "apps") getGroupApplications(id).then(setApps).catch((e) => setError(errorMessage(e)));
  }, [id, tab]);
  useEffect(() => {
    if (tab === "roles") listRoles().then(setAllRoles).catch(() => undefined);
  }, [tab]);

  function openRoles() {
    setRoleSel(group ? [...group.roleNames] : []);
    setRolesOpen(true);
  }
  function toggleRole(name: string) {
    setRoleSel((sel) => (sel.includes(name) ? sel.filter((r) => r !== name) : [...sel, name]));
  }
  async function saveRoles() {
    try {
      const updated = await setGroupRoles(id, roleSel);
      setGroup(updated);
      setRolesOpen(false);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <>
      <Link to="/admin/groups" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t("groupDetailBack")}
      </Link>
      <PageHeader
        title={group ? group.name : t("groupDetailFallbackName")}
        description={group?.description || t("groupDetailFallbackDescription")}
        actions={group?.system
          ? <Badge variant="secondary"><Lock className="size-3" /> {t("groupDetailSystemBadge")}</Badge>
          : undefined}
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      <div role="tablist" className="mb-4 flex gap-1 border-b">
        {(["members", "roles", "apps"] as Tab[]).map((key) => (
          <button key={key} role="tab" aria-selected={tab === key} onClick={() => setTab(key)}
                  className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${tab === key ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"}`}>
            {key === "members" ? `${t("groupDetailTabMembers")}${members ? ` (${members.total})` : ""}`
              : key === "roles" ? `${t("groupDetailTabRoles")}${group ? ` (${group.roleNames.length})` : ""}`
              : t("groupDetailTabApps")}
          </button>
        ))}
      </div>

      {tab === "members" && (
        <>
          <Table>
            <TableHeader><TableRow><TableHead>{t("groupDetailColUsername")}</TableHead></TableRow></TableHeader>
            <TableBody>
              {members === null ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <TableRow key={i}><TableCell><Skeleton className="h-5 w-48" /></TableCell></TableRow>
                ))
              ) : members.items.length === 0 ? (
                <TableRow><TableCell className="text-muted-foreground">{t("groupDetailNoMembers")}</TableCell></TableRow>
              ) : members.items.map((m) => (
                <TableRow key={m.id}><TableCell className="font-medium">{m.label}</TableCell></TableRow>
              ))}
            </TableBody>
          </Table>
          <Pagination page={page} size={SIZE} total={members?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "roles" && group && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <p className="text-sm text-muted-foreground">{t("groupDetailRolesHint")}</p>
            <Button variant="outline" size="sm" disabled={group.system} onClick={openRoles}>
              <ShieldCheck className="size-4" /> {t("groupDetailEditRoles")}
            </Button>
          </div>
          {group.roleNames.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("groupDetailNoRoles")}</p>
          ) : (
            <div className="flex flex-wrap gap-1">
              {group.roleNames.map((r) => <Badge key={r} variant="secondary">{r}</Badge>)}
            </div>
          )}
          <div className="border-t pt-4"><MetadataEditor kind="groups" entityId={id} /></div>
        </div>
      )}

      {tab === "apps" && (
        <Table>
          <TableHeader><TableRow><TableHead>{t("groupDetailColApplication")}</TableHead><TableHead>{t("groupDetailColType")}</TableHead></TableRow></TableHeader>
          <TableBody>
            {apps === null ? (
              Array.from({ length: 4 }).map((_, i) => (
                <TableRow key={i}><TableCell><Skeleton className="h-5 w-48" /></TableCell><TableCell><Skeleton className="h-5 w-16" /></TableCell></TableRow>
              ))
            ) : apps.length === 0 ? (
              <TableRow><TableCell colSpan={2} className="text-muted-foreground">{t("groupDetailNoApps")}</TableCell></TableRow>
            ) : apps.map((a) => (
              <TableRow key={`${a.type}:${a.id}`}>
                <TableCell className="font-medium"><span className="inline-flex items-center gap-2"><AppWindow className="size-4 text-muted-foreground" />{a.name}</span></TableCell>
                <TableCell><Badge variant="muted">{a.type}</Badge></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <Dialog open={rolesOpen} onOpenChange={setRolesOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("groupDetailDelegateTitle")}</DialogTitle>
            <DialogDescription>
              <Trans
                t={t}
                i18nKey="groupDetailDelegateDescription"
                values={{ name: group?.name ?? "" }}
                components={[<strong key="0" />]}
              />
            </DialogDescription>
          </DialogHeader>
          {allRoles.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("groupDetailNoRolesAvailable")}</p>
          ) : (
            <div className="grid max-h-72 grid-cols-1 gap-1 overflow-y-auto sm:grid-cols-2">
              {allRoles.map((role) => {
                const checked = roleSel.includes(role.name);
                return (
                  <label key={role.id} className="flex cursor-pointer items-center gap-2.5 rounded-md border p-2.5 text-sm transition-colors hover:bg-muted/60 has-[:checked]:border-primary has-[:checked]:bg-accent">
                    <Checkbox className="size-4" checked={checked} onCheckedChange={() => toggleRole(role.name)} />
                    <span>{role.name}</span>
                  </label>
                );
              })}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setRolesOpen(false)}>{t("cancel")}</Button>
            <Button onClick={saveRoles}>{t("groupDetailSaveRoles")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
