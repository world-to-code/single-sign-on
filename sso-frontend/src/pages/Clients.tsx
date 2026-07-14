import { Link } from "react-router-dom";
import { Trans, useTranslation } from "react-i18next";
import { ExternalLink, Lock, Plus, Trash2 } from "lucide-react";
import { type ClientRow } from "@/clients";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { TagList } from "@/components/TagList";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { tokens } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/** Derive the app's origin from its first redirect URI, to "launch" the application. */
function launchUrl(client: ClientRow): string | null {
  const first = (client.redirectUris ?? "").split(",")[0]?.trim();
  if (!first) return null;
  try {
    return new URL(first).origin;
  } catch {
    return null;
  }
}

export default function Clients() {
  const { t } = useTranslation(["console", "states"]);
  const confirmDelete = useDeleteConfirm();
  const { items: clients, total, page, setPage, size, error, reload } = usePaginated<ClientRow>("/api/admin/clients");

  async function remove(client: ClientRow) {
    await confirmDelete({
      title: t("clientsDeleteTitle"),
      description: t("clientsDeleteDescription", { clientId: client.clientId }),
      path: `/api/admin/clients/${client.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title={t("clientsTitle")}
        description={t("clientsDescription")}
        actions={<Button asChild><Link to="/admin/clients/new"><Plus /> {t("clientsNew")}</Link></Button>}
      />

      <DataList
        data={clients}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={
          <EmptyState icon={<ExternalLink className="size-8" />} title={t("states:clientsEmptyTitle")}
                      hint={t("states:clientsEmptyHint")} />
        }
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("clientsColClientId")}</TableHead>
                <TableHead>{t("clientsColName")}</TableHead>
                <TableHead>{t("clientsColScopes")}</TableHead>
                <TableHead>{t("clientsColGrantTypes")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((c) => {
                const url = launchUrl(c);
                return (
                  <TableRow key={c.id}>
                    <TableCell className="font-medium">
                      <span className="inline-flex items-center gap-1.5">
                        <Link to={`/admin/clients/${c.id}`} className="text-primary hover:underline">{c.clientId}</Link>
                        {url && (
                          <a href={url} target="_blank" rel="noreferrer" title={t("clientsOpen", { url })}
                             className="text-muted-foreground hover:text-foreground">
                            <ExternalLink className="size-3.5" />
                          </a>
                        )}
                      </span>
                    </TableCell>
                    <TableCell>{c.clientName}</TableCell>
                    <TableCell><TagList items={tokens(c.scopes)} variant="secondary" /></TableCell>
                    <TableCell><TagList items={tokens(c.grantTypes)} variant="muted" /></TableCell>
                    <TableCell className="text-right">
                      {c.clientId === "admin-console" ? (
                        <Badge variant="secondary" title={t("clientsProtectedTitle")}>
                          <Lock className="size-3" /> {t("clientsProtected")}
                        </Badge>
                      ) : (
                        <Button variant="ghost" size="icon" onClick={() => remove(c)}
                                className="text-muted-foreground hover:text-destructive"><Trash2 /></Button>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />

      <p className="mt-4 max-w-3xl text-sm text-muted-foreground">
        <Trans
          t={t}
          i18nKey="clientsKeycloakHint"
          values={{
            discovery: `${location.origin}/.well-known/openid-configuration`,
            broker: ".../realms/<realm>/broker/<alias>/endpoint",
          }}
          components={[
            <code key="0" className="rounded bg-muted px-1 py-0.5 font-mono text-xs" />,
            <code key="1" className="rounded bg-muted px-1 py-0.5 font-mono text-xs" />,
          ]}
        />
      </p>
    </>
  );
}
