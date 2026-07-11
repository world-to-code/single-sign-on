import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { KeyRound, Plus } from "lucide-react";
import { type AdminUser } from "@/users";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

export default function Users() {
  const { t } = useTranslation(["console", "states"]);
  const { items: users, total, page, setPage, size, error } = usePaginated<AdminUser>("/api/admin/users");

  return (
    <>
      <PageHeader
        title={t("usersTitle")}
        description={total ? t("usersCount", { count: total }) : t("usersDescription")}
        actions={<Button asChild><Link to="/admin/users/new"><Plus /> {t("usersNew")}</Link></Button>}
      />

      <DataList
        data={users}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<KeyRound className="size-8" />} title={t("states:usersEmptyTitle")} hint={t("states:usersEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("usersColUsername")}</TableHead>
                <TableHead>{t("usersColEmail")}</TableHead>
                <TableHead>{t("usersColDisplay")}</TableHead>
                <TableHead>{t("usersColStatus")}</TableHead>
                <TableHead>{t("usersColRoles")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className="font-medium">
                    <Link to={`/admin/users/${u.id}`} className="text-primary hover:underline">{u.username}</Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{u.email}</TableCell>
                  <TableCell>{u.displayName ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell><Badge variant={u.enabled ? "success" : "muted"}>{u.enabled ? t("usersStatusEnabled") : t("usersStatusDisabled")}</Badge></TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {u.roles.length ? u.roles.map((r) => <Badge key={r} variant="secondary">{r}</Badge>) : <span className="text-muted-foreground">—</span>}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />
    </>
  );
}
