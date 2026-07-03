import { useEffect, useState } from "react";
import { ExternalLink, Pencil, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost, apiPut } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface RelyingParty {
  id: string;
  entityId: string;
  displayName: string | null;
  acsUrl: string;
  nameIdFormat: string;
  signAssertion: boolean;
  signResponse: boolean;
  encryptAssertion: boolean;
  signatureAlgorithm: string;
  dataEncryptionAlgorithm: string;
  keyTransportAlgorithm: string;
  wantAuthnRequestsSigned: boolean;
  allowIdpInitiated: boolean;
  signingCertificate: string | null;
  encryptionCertificate: string | null;
  spLoginUrl: string | null;
}

const blank = {
  id: null as string | null,
  entityId: "", displayName: "", acsUrl: "", nameIdFormat: "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress",
  signAssertion: true, signResponse: false, encryptAssertion: false,
  signatureAlgorithm: "RSA_SHA256", dataEncryptionAlgorithm: "AES256_GCM", keyTransportAlgorithm: "RSA_OAEP",
  wantAuthnRequestsSigned: false, allowIdpInitiated: true,
  signingCertificate: "", encryptionCertificate: "", spLoginUrl: "",
};
type Editor = typeof blank;

export default function RelyingParties() {
  const confirmDelete = useDeleteConfirm();
  const [rps, setRps] = useState<RelyingParty[] | null>(null);

  const {
    editor, set, setEditor, open, setOpen, error, setError, openCreate, openEdit, save,
  } = useEditorForm<Editor>({
    blank,
    toRequest: (e) => e,
    create: (body) => apiPost("/api/admin/saml/relying-parties", body),
    update: (id, body) => apiPut(`/api/admin/saml/relying-parties/${id}`, body),
    onSaved: reload,
  });

  function reload() {
    apiGet<RelyingParty[]>("/api/admin/saml/relying-parties").then(setRps).catch((e) => setError(String(e)));
  }
  useEffect(reload, []);

  function edit(rp: RelyingParty) {
    openEdit({ ...rp, displayName: rp.displayName ?? "", signingCertificate: rp.signingCertificate ?? "", encryptionCertificate: rp.encryptionCertificate ?? "", spLoginUrl: rp.spLoginUrl ?? "" });
  }

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
        actions={<Button onClick={openCreate}><Plus /> New relying party</Button>}
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
        error={error}
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
                      <Button variant="ghost" size="icon" onClick={() => edit(rp)}><Pencil /></Button>
                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive" onClick={() => remove(rp)}><Trash2 /></Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{editor.id ? `Edit: ${editor.entityId}` : "Register a relying party"}</DialogTitle>
            <DialogDescription>Endpoint and per-RP SAML security configuration.</DialogDescription>
          </DialogHeader>

          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-5">
            <section className="space-y-3">
              <div className="space-y-2">
                <Label htmlFor="rp-entity">Entity ID</Label>
                <Input id="rp-entity" value={editor.entityId} disabled={!!editor.id} required
                       onChange={(e) => set({ entityId: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="rp-display">Display name</Label>
                <Input id="rp-display" value={editor.displayName} placeholder="Friendly name shown in app lists (defaults to Entity ID)"
                       onChange={(e) => set({ displayName: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="rp-acs">ACS URL</Label>
                <Input id="rp-acs" value={editor.acsUrl} required onChange={(e) => set({ acsUrl: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="rp-nameid">NameID format</Label>
                <Input id="rp-nameid" value={editor.nameIdFormat} onChange={(e) => set({ nameIdFormat: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="rp-sp-login">SP-initiated login URL</Label>
                <Input id="rp-sp-login" value={editor.spLoginUrl} placeholder="https://sp.example.com/login (blank = IdP-initiated)"
                       onChange={(e) => set({ spLoginUrl: e.target.value })} />
                <p className="text-xs text-muted-foreground">
                  Portal “launch” redirects here so the SP starts SP-initiated SSO (sends us an AuthnRequest).
                  Leave blank to fall back to IdP-initiated (unsolicited) SSO.
                </p>
              </div>
            </section>

            <Separator />
            <section className="space-y-3">
              <h4 className="text-sm font-semibold">Signing</h4>
              <div className="flex items-center gap-2">
                <Switch id="rp-sign-assert" checked={editor.signAssertion} onCheckedChange={(v) => set({ signAssertion: v })} />
                <Label htmlFor="rp-sign-assert">Sign assertion</Label>
              </div>
              <div className="flex items-center gap-2">
                <Switch id="rp-sign-resp" checked={editor.signResponse} onCheckedChange={(v) => set({ signResponse: v })} />
                <Label htmlFor="rp-sign-resp">Sign response</Label>
              </div>
              <div className="space-y-2">
                <Label htmlFor="rp-sig-alg">Signature algorithm</Label>
                <Select id="rp-sig-alg" value={editor.signatureAlgorithm} onChange={(e) => set({ signatureAlgorithm: e.target.value })}>
                  <option>RSA_SHA256</option><option>RSA_SHA512</option><option value="RSA_SHA1">RSA_SHA1 (legacy)</option>
                </Select>
              </div>
            </section>

            <Separator />
            <section className="space-y-3">
              <h4 className="text-sm font-semibold">Assertion encryption</h4>
              <div className="flex items-center gap-2">
                <Switch id="rp-encrypt" checked={editor.encryptAssertion} onCheckedChange={(v) => set({ encryptAssertion: v })} />
                <Label htmlFor="rp-encrypt">Encrypt assertion</Label>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="rp-data-alg">Data algorithm</Label>
                  <Select id="rp-data-alg" value={editor.dataEncryptionAlgorithm} onChange={(e) => set({ dataEncryptionAlgorithm: e.target.value })}>
                    <option>AES256_GCM</option><option>AES128_GCM</option>
                    <option value="AES256_CBC">AES256_CBC (legacy)</option><option value="AES128_CBC">AES128_CBC (legacy)</option>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="rp-key-alg">Key transport</Label>
                  <Select id="rp-key-alg" value={editor.keyTransportAlgorithm} onChange={(e) => set({ keyTransportAlgorithm: e.target.value })}>
                    <option>RSA_OAEP</option><option value="RSA_1_5">RSA_1_5 (legacy)</option>
                  </Select>
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="rp-enc-cert">SP encryption certificate (PEM)</Label>
                <Textarea id="rp-enc-cert" rows={3} value={editor.encryptionCertificate}
                          onChange={(e) => set({ encryptionCertificate: e.target.value })} />
              </div>
            </section>

            <Separator />
            <section className="space-y-3">
              <h4 className="text-sm font-semibold">Inbound AuthnRequest</h4>
              <div className="flex items-center gap-2">
                <Switch id="rp-want-signed" checked={editor.wantAuthnRequestsSigned} onCheckedChange={(v) => set({ wantAuthnRequestsSigned: v })} />
                <Label htmlFor="rp-want-signed">Require signed AuthnRequests</Label>
              </div>
              <div className="space-y-2">
                <Label htmlFor="rp-sign-cert">SP signing certificate (PEM)</Label>
                <Textarea id="rp-sign-cert" rows={3} value={editor.signingCertificate}
                          onChange={(e) => set({ signingCertificate: e.target.value })} />
              </div>
            </section>

            <Separator />
            <div className="flex items-center gap-2">
              <Switch id="rp-idp-init" checked={editor.allowIdpInitiated} onCheckedChange={(v) => set({ allowIdpInitiated: v })} />
              <Label htmlFor="rp-idp-init">Allow IdP-initiated SSO</Label>
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => { setEditor(blank); setOpen(false); }}>Cancel</Button>
              <Button type="submit">{editor.id ? "Save changes" : "Register"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
