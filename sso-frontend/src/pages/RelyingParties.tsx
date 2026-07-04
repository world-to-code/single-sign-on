import { Link } from "react-router-dom";
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
  const confirmDelete = useDeleteConfirm();
  const { items: rps, total, page, setPage, size, error: listError, reload } =
    usePaginated<RelyingParty>("/api/admin/saml/relying-parties");

  async function remove(rp: RelyingParty) {
    await confirmDelete({
      title: "Delete relying party?",
      description: `SAML SP "${rp.entityId}" will be removed.`,
      path: `/api/admin/saml/relying-parties/${rp.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title="SAML Relying Parties"
        description="Service providers that trust this IdP, with their per-RP signing and encryption settings."
        actions={<Button asChild><Link to="/admin/relying-parties/new"><Plus /> New relying party</Link></Button>}
      />

      <Alert variant="info" className="mb-4">
        <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
          <span>
            <strong>This IdP's SAML metadata</strong> — import it into your SP to establish trust. It carries the IdP
            entityID, the SSO endpoint, and the <strong>signing certificate</strong> the SP verifies assertions with.
            The SP's own certificate (for signed AuthnRequests / assertion encryption) goes in the relying party below.
          </span>
          <a className="inline-flex shrink-0 items-center gap-1.5 font-medium underline"
             href="/saml2/idp/metadata" target="_blank" rel="noreferrer">
            <ExternalLink className="size-4" /> View / download IdP metadata
          </a>
        </AlertDescription>
      </Alert>

      <DataList
        data={rps}
        error={listError}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState title="No relying parties" hint="Register a SAML service provider to issue assertions to it." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Entity ID</TableHead>
                <TableHead>ACS URL</TableHead>
                <TableHead>Security</TableHead>
                <TableHead>IdP-init</TableHead>
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
                      {rp.signAssertion && <Badge variant="secondary">sign assertion</Badge>}
                      {rp.signResponse && <Badge variant="secondary">sign response</Badge>}
                      {rp.encryptAssertion && <Badge variant="default">encrypt · {rp.dataEncryptionAlgorithm}/{rp.keyTransportAlgorithm}</Badge>}
                      {rp.wantAuthnRequestsSigned && <Badge variant="muted">verify AuthnReq</Badge>}
                      {!rp.signAssertion && !rp.signResponse && !rp.encryptAssertion && !rp.wantAuthnRequestsSigned && (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    {rp.allowIdpInitiated ? (
                      <a className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
                         href={`/saml2/idp/sso/init?sp=${encodeURIComponent(rp.entityId)}`} target="_blank" rel="noreferrer">
                        launch <ExternalLink className="size-3" />
                      </a>
                    ) : <Badge variant="muted">off</Badge>}
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
