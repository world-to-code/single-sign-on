import { Link } from "react-router-dom";
import { Trans, useTranslation } from "react-i18next";
import { ExternalLink, Pencil, Plus, Trash2 } from "lucide-react";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";

interface RelyingParty {
  id: string;
  entityId: string;
  displayName: string | null;
  acsUrl: string;
  signAssertion: boolean;
  signResponse: boolean;
  encryptAssertion: boolean;
  dataEncryptionAlgorithm: string;
  keyTransportAlgorithm: string;
  wantAuthnRequestsSigned: boolean;
  allowIdpInitiated: boolean;
}

export default function RelyingParties() {
  const { t } = useTranslation(["console", "states"]);
  const confirmDelete = useDeleteConfirm();
  const { items: rps, total, page, setPage, size, error: listError, reload } =
    usePaginated<RelyingParty>("/api/admin/saml/relying-parties");

  async function remove(rp: RelyingParty) {
    await confirmDelete({
      title: t("relyingPartiesDeleteTitle"),
      description: t("relyingPartiesDeleteDescription", { entityId: rp.entityId }),
      path: `/api/admin/saml/relying-parties/${rp.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title={t("relyingPartiesTitle")}
        description={t("relyingPartiesDescription")}
        actions={<Button asChild><Link to="/admin/relying-parties/new"><Plus /> {t("relyingPartiesNew")}</Link></Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
          <span>
            <Trans t={t} i18nKey="relyingPartiesInfo" components={[<strong key="0" />, <strong key="1" />]} />
          </span>
          <a className="inline-flex shrink-0 items-center gap-1.5 font-medium underline"
             href="/saml2/idp/metadata" target="_blank" rel="noreferrer">
            <ExternalLink className="size-4" /> {t("relyingPartiesViewMetadata")}
          </a>
        </AlertDescription>
      </Alert>

      <DataList
        data={rps}
        error={listError}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title={t("states:relyingPartiesEmptyTitle")} hint={t("states:relyingPartiesEmptyHint")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("relyingPartiesColEntityId")}</TableHead>
                <TableHead>{t("relyingPartiesColAcsUrl")}</TableHead>
                <TableHead>{t("relyingPartiesColSecurity")}</TableHead>
                <TableHead>{t("relyingPartiesColIdpInit")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((rp) => (
                <TableRow key={rp.id}>
                  <TableCell className="font-medium">
                    {rp.displayName || rp.entityId}
                    {rp.displayName && <div className="text-xs font-normal text-muted-foreground">{rp.entityId}</div>}
                  </TableCell>
                  <TableCell className="max-w-xs truncate text-muted-foreground" title={rp.acsUrl}>{rp.acsUrl}</TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {rp.signAssertion && <Badge variant="secondary">{t("relyingPartiesSignAssertion")}</Badge>}
                      {rp.signResponse && <Badge variant="secondary">{t("relyingPartiesSignResponse")}</Badge>}
                      {rp.encryptAssertion && <Badge variant="default">{t("relyingPartiesEncrypt")} · {rp.dataEncryptionAlgorithm}/{rp.keyTransportAlgorithm}</Badge>}
                      {rp.wantAuthnRequestsSigned && <Badge variant="muted">{t("relyingPartiesVerifyAuthnReq")}</Badge>}
                      {!rp.signAssertion && !rp.signResponse && !rp.encryptAssertion && !rp.wantAuthnRequestsSigned && (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    {rp.allowIdpInitiated ? (
                      <a className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
                         href={`/saml2/idp/sso/init?sp=${encodeURIComponent(rp.entityId)}`} target="_blank" rel="noreferrer">
                        {t("relyingPartiesLaunch")} <ExternalLink className="size-3" />
                      </a>
                    ) : <Badge variant="muted">{t("relyingPartiesIdpInitOff")}</Badge>}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="icon" asChild><Link to={`/admin/relying-parties/${rp.id}`}><Pencil /></Link></Button>
                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(rp)}><Trash2 /></Button>
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
