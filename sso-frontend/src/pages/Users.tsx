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
  const { t } = useTranslation("states");
  const { items: users, total, page, setPage, size, error } = usePaginated<AdminUser>("/api/admin/users");

  return (
    <>
      <PageHeader
        title="Users"
        description={total ? `${total} user${total === 1 ? "" : "s"} in the directory.` : "Manage directory users."}
        actions={<Button asChild><Link to="/admin/users/new"><Plus /> New user</Link></Button>}
      />

      <DataList
        data={users}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<KeyRound className="size-8" />} title={t("usersEmptyTitle")} hint={t("usersEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Username</TableHead>
                <TableHead>Email</TableHead>
                <TableHead>Display</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Roles</TableHead>
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
                  <TableCell><Badge variant={u.enabled ? "success" : "muted"}>{u.enabled ? "enabled" : "disabled"}</Badge></TableCell>
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
