import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ExternalLink, Lock, Plus, Trash2 } from "lucide-react";
import { type ClientRow } from "@/clients";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
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
  const { t } = useTranslation("states");
  const confirmDelete = useDeleteConfirm();
  const { items: clients, total, page, setPage, size, error, reload } = usePaginated<ClientRow>("/api/admin/clients");

  async function remove(client: ClientRow) {
    await confirmDelete({
      title: "Delete client?",
      description: `OAuth2 client "${client.clientId}" will be removed and can no longer authenticate.`,
      path: `/api/admin/clients/${client.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title="OAuth2 / OIDC Clients"
        description="Applications that delegate authentication to this identity provider."
        actions={<Button asChild><Link to="/admin/clients/new"><Plus /> New client</Link></Button>}
      />

      <DataList
        data={clients}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={
          <EmptyState icon={<ExternalLink className="size-8" />} title={t("clientsEmptyTitle")}
                      hint={t("clientsEmptyHint")} />
        }
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Client ID</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Scopes</TableHead>
                <TableHead>Grant types</TableHead>
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
                          <a href={url} target="_blank" rel="noreferrer" title={`Open ${url}`}
                             className="text-muted-foreground hover:text-foreground">
                            <ExternalLink className="size-3.5" />
                          </a>
                        )}
                      </span>
                    </TableCell>
                    <TableCell>{c.clientName}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {tokens(c.scopes).map((s) => <Badge key={s} variant="secondary">{s}</Badge>)}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {tokens(c.grantTypes).map((g) => <Badge key={g} variant="muted">{g}</Badge>)}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      {c.clientId === "admin-console" ? (
                        <Badge variant="secondary" title="First-party admin console — protected from deletion">
                          <Lock className="size-3" /> Protected
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
        Keycloak: add this server as an OIDC Identity Provider using discovery{" "}
        <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">{location.origin}/.well-known/openid-configuration</code>, the client_id/secret created here,
        and set the redirect URI above to Keycloak's broker endpoint
        (<code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">.../realms/&lt;realm&gt;/broker/&lt;alias&gt;/endpoint</code>).
      </p>
    </>
  );
}
