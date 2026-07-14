import { useEffect, useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ChevronRight, Pencil, Plus, Trash2 } from "lucide-react";
import { apiGet, type Page } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { TagList } from "@/components/TagList";
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
  const { t } = useTranslation(["console", "states"]);
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
      title: t("authPoliciesDeleteTitle"),
      description: t("authPoliciesDeleteDescription", { name: p.name }),
      path: `/api/admin/auth-policies/${p.id}`,
      onDeleted: reload,
    });
  }

  const roleName = (id: string) => roles.find((r) => r.id === id)?.name ?? id;
  const userName = (id: string) => userNames[id] ?? id;

  return (
    <>
      <PageHeader
        title={t("authPoliciesTitle")}
        description={t("authPoliciesDescription")}
        actions={<Button asChild><Link to="/admin/auth-policies/new"><Plus /> {t("authPoliciesNew")}</Link></Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription>
          <Trans t={t} i18nKey="authPoliciesInfo" components={[<strong key="0" />, <strong key="1" />]} />
        </AlertDescription>
      </Alert>

      <DataList
        data={policies}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title={t("states:authPoliciesEmptyTitle")} hint={t("states:authPoliciesEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("authPoliciesColName")}</TableHead>
                <TableHead>{t("authPoliciesColPriority")}</TableHead>
                <TableHead>{t("authPoliciesColEnabled")}</TableHead>
                <TableHead>{t("authPoliciesColChain")}</TableHead>
                <TableHead>{t("authPoliciesColRoles")}</TableHead>
                <TableHead>{t("authPoliciesColUsers")}</TableHead>
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
                        ? <Badge variant="outline">{t("authPoliciesAppOnly")}</Badge>
                        : (p.assignedRoleIds.length === 0 && p.assignedUserIds.length === 0)
                          ? <Badge variant="default">{t("badgeGlobal")}</Badge> : null}
                      {p.appliesToLogin && !p.allowEnrollmentAtLogin && <Badge variant="muted">{t("authPoliciesNoSelfEnroll")}</Badge>}
                    </div>
                  </TableCell>
                  <TableCell><Badge variant="muted">{p.priority}</Badge></TableCell>
                  <TableCell>
                    <Badge variant={p.enabled ? "success" : "muted"}>{p.enabled ? t("badgeEnabled") : t("badgeDisabled")}</Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap items-center gap-1">
                      {p.steps.map((s, i) => (
                        <span key={i} className="flex items-center gap-1">
                          {i > 0 && <ChevronRight className="size-3 text-muted-foreground" />}
                          <Badge variant="secondary">{s.join(` ${t("authPoliciesStepOr")} `)}</Badge>
                        </span>
                      ))}
                    </div>
                  </TableCell>
                  <TableCell><TagList items={p.assignedRoleIds.map(roleName)} variant="secondary" /></TableCell>
                  <TableCell><TagList items={p.assignedUserIds.map(userName)} variant="secondary" /></TableCell>
                  <TableCell className="text-right">
                    {p.name !== "Default" ? (
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="icon" asChild><Link to={`/admin/auth-policies/${p.id}`}><Pencil /></Link></Button>
                        <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(p)}><Trash2 /></Button>
                      </div>
                    ) : (
                      <Badge variant="outline">{t("badgeBuiltIn")}</Badge>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      <p className="mt-4 text-sm text-muted-foreground">{t("authPoliciesFactorsHint")}</p>
    </>
  );
}
