import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { ExternalLink, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost } from "../api";
import { PageHeader } from "@/components/PageHeader";
import { DataList, EmptyState } from "@/components/states";
import { Field, Toggle } from "@/components/form/fields";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { tokens } from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";

interface ClientView {
  id: string;
  clientId: string;
  clientName: string;
  scopes: string;
  grantTypes: string;
  redirectUris: string;
}

/** Derive the app's origin from its first redirect URI, to "launch" the application. */
function launchUrl(client: ClientView): string | null {
  const first = (client.redirectUris ?? "").split(",")[0]?.trim();
  if (!first) return null;
  try {
    return new URL(first).origin;
  } catch {
    return null;
  }
}

const empty = {
  clientId: "",
  clientName: "",
  redirectUris: "",
  postLogoutRedirectUris: "",
  scopes: "openid profile email",
  grantTypes: "authorization_code refresh_token",
  clientAuthenticationMethods: "client_secret_basic client_secret_post",
  publicClient: false,
  requireConsent: true,
  requireProofKey: false,
  accessTokenMinutes: "30",
  refreshTokenDays: "7",
  authorizationCodeMinutes: "5",
  deviceCodeMinutes: "5",
  reuseRefreshTokens: false,
  accessTokenFormat: "SELF_CONTAINED",
  idTokenSignatureAlgorithm: "RS256",
  tokenEndpointAuthSigningAlgorithm: "",
  jwkSetUrl: "",
  x509SubjectDn: "",
  x509BoundAccessTokens: false,
  clientSecretDays: "",
};

export default function Clients() {
  const confirmDelete = useDeleteConfirm();
  const [clients, setClients] = useState<ClientView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({ ...empty });
  const [createdSecret, setCreatedSecret] = useState<{ clientId: string; clientSecret: string | null } | null>(null);
  const [open, setOpen] = useState(false);

  const set = (patch: Partial<typeof empty>) => setForm((f) => ({ ...f, ...patch }));

  function reload() {
    apiGet<ClientView[]>("/api/admin/clients").then(setClients).catch((e) => setError(String(e)));
  }
  useEffect(reload, []);

  async function create(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setCreatedSecret(null);
    try {
      const num = (v: string): number | null => (v.trim() ? Number(v) : null);
      const result = await apiPost<{ clientId: string; clientSecret: string | null }>("/api/admin/clients", {
        clientId: form.clientId,
        clientName: form.clientName || null,
        redirectUris: tokens(form.redirectUris),
        postLogoutRedirectUris: tokens(form.postLogoutRedirectUris),
        scopes: tokens(form.scopes),
        grantTypes: tokens(form.grantTypes),
        clientAuthenticationMethods: tokens(form.clientAuthenticationMethods),
        publicClient: form.publicClient,
        requireConsent: form.requireConsent,
        requireProofKey: form.requireProofKey,
        accessTokenMinutes: num(form.accessTokenMinutes),
        refreshTokenDays: num(form.refreshTokenDays),
        authorizationCodeMinutes: num(form.authorizationCodeMinutes),
        deviceCodeMinutes: num(form.deviceCodeMinutes),
        reuseRefreshTokens: form.reuseRefreshTokens,
        accessTokenFormat: form.accessTokenFormat,
        idTokenSignatureAlgorithm: form.idTokenSignatureAlgorithm,
        tokenEndpointAuthSigningAlgorithm: form.tokenEndpointAuthSigningAlgorithm || null,
        jwkSetUrl: form.jwkSetUrl || null,
        x509SubjectDn: form.x509SubjectDn || null,
        x509BoundAccessTokens: form.x509BoundAccessTokens,
        clientSecretDays: num(form.clientSecretDays),
      });
      setCreatedSecret(result);
      setForm({ ...empty });
      setOpen(false);
      reload();
    } catch (e) {
      setError(String(e));
    }
  }

  async function remove(client: ClientView) {
    await confirmDelete({
      title: "Delete client?",
      description: `OAuth2 client "${client.clientId}" will be removed and can no longer authenticate.`,
      path: `/api/admin/clients/${client.id}`,
      onDeleted: reload,
    });
  }

  const newClientButton = (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button><Plus /> New client</Button>
      </DialogTrigger>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Register an OAuth2 / OIDC client</DialogTitle>
          <DialogDescription>Confidential clients receive a secret shown once; public clients use PKCE.</DialogDescription>
        </DialogHeader>

        {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

        <form id="client-form" onSubmit={create} className="grid gap-4">
          <Field label="Client ID">
            <Input value={form.clientId} onChange={(e) => set({ clientId: e.target.value })} placeholder="my-app" required />
          </Field>
          <Field label="Client name">
            <Input value={form.clientName} onChange={(e) => set({ clientName: e.target.value })} placeholder="My Application" />
          </Field>
          <Field label="Redirect URIs" hint="Space or comma separated.">
            <Textarea rows={2} value={form.redirectUris} onChange={(e) => set({ redirectUris: e.target.value })}
                      placeholder="https://app.example.com/callback" />
          </Field>
          <Field label="Scopes">
            <Input value={form.scopes} onChange={(e) => set({ scopes: e.target.value })} />
          </Field>
          <Field label="Grant types">
            <Input value={form.grantTypes} onChange={(e) => set({ grantTypes: e.target.value })} />
          </Field>

          <div className="grid gap-2">
            <Toggle label="Public client" hint="PKCE, no secret." checked={form.publicClient} onChange={(v) => set({ publicClient: v })} />
            <Toggle label="Require consent" checked={form.requireConsent} onChange={(v) => set({ requireConsent: v })} />
            <Toggle label="Require PKCE (confidential)" checked={form.requireProofKey} onChange={(v) => set({ requireProofKey: v })} />
          </div>

          <details className="rounded-lg border p-3">
            <summary className="cursor-pointer text-sm font-medium">Advanced security settings</summary>
            <Separator className="my-3" />
            <div className="grid gap-4">
              <Field label="Post-logout redirect URIs">
                <Textarea rows={1} value={form.postLogoutRedirectUris} onChange={(e) => set({ postLogoutRedirectUris: e.target.value })} />
              </Field>
              <Field label="Client authentication methods">
                <Input value={form.clientAuthenticationMethods} onChange={(e) => set({ clientAuthenticationMethods: e.target.value })}
                       placeholder="client_secret_basic client_secret_post private_key_jwt ..." />
              </Field>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Access token TTL (min)">
                  <Input value={form.accessTokenMinutes} onChange={(e) => set({ accessTokenMinutes: e.target.value })} inputMode="numeric" />
                </Field>
                <Field label="Refresh token TTL (days)">
                  <Input value={form.refreshTokenDays} onChange={(e) => set({ refreshTokenDays: e.target.value })} inputMode="numeric" />
                </Field>
                <Field label="Authorization code TTL (min)">
                  <Input value={form.authorizationCodeMinutes} onChange={(e) => set({ authorizationCodeMinutes: e.target.value })} inputMode="numeric" />
                </Field>
                <Field label="Device code TTL (min)">
                  <Input value={form.deviceCodeMinutes} onChange={(e) => set({ deviceCodeMinutes: e.target.value })} inputMode="numeric" />
                </Field>
              </div>
              <Field label="Access token format">
                <Select value={form.accessTokenFormat} onChange={(e) => set({ accessTokenFormat: e.target.value })}>
                  <option>SELF_CONTAINED</option><option>REFERENCE</option>
                </Select>
              </Field>
              <Field label="ID token signature algorithm">
                <Input value={form.idTokenSignatureAlgorithm} onChange={(e) => set({ idTokenSignatureAlgorithm: e.target.value })}
                       placeholder="RS256 / ES256 / RS384 ..." />
              </Field>
              <Field label="Token-endpoint auth signing alg (JWT client auth)">
                <Input value={form.tokenEndpointAuthSigningAlgorithm} onChange={(e) => set({ tokenEndpointAuthSigningAlgorithm: e.target.value })}
                       placeholder="RS256 (private_key_jwt) / HS256 (client_secret_jwt)" />
              </Field>
              <Field label="JWK Set URL (private_key_jwt)">
                <Input value={form.jwkSetUrl} onChange={(e) => set({ jwkSetUrl: e.target.value })}
                       placeholder="https://client/.well-known/jwks.json" />
              </Field>
              <Field label="X.509 subject DN (tls_client_auth)">
                <Input value={form.x509SubjectDn} onChange={(e) => set({ x509SubjectDn: e.target.value })} />
              </Field>
              <Toggle label="Certificate-bound (mTLS) access tokens" checked={form.x509BoundAccessTokens} onChange={(v) => set({ x509BoundAccessTokens: v })} />
              <Field label="Client secret expiry (days)" hint="Blank = never.">
                <Input value={form.clientSecretDays} onChange={(e) => set({ clientSecretDays: e.target.value })} inputMode="numeric" />
              </Field>
            </div>
          </details>
        </form>

        <DialogFooter>
          <Button type="submit" form="client-form">Create client</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );

  return (
    <>
      <PageHeader
        title="OAuth2 / OIDC Clients"
        description="Applications that delegate authentication to this identity provider."
        actions={newClientButton}
      />

      {createdSecret && (
        <Alert variant="success" className="mb-4">
          <AlertTitle>Client "{createdSecret.clientId}" created</AlertTitle>
          <AlertDescription>
            {createdSecret.clientSecret ? (
              <>
                <p className="mb-2 text-muted-foreground">Secret (shown once — copy now):</p>
                <code className="block break-all rounded-md bg-muted px-3 py-2 font-mono text-xs text-foreground">
                  {createdSecret.clientSecret}
                </code>
              </>
            ) : (
              <p>Public client (PKCE, no secret).</p>
            )}
          </AlertDescription>
        </Alert>
      )}

      <DataList
        data={clients}
        error={error}
        isEmpty={(items) => items.length === 0}
        empty={
          <EmptyState icon={<ExternalLink className="size-8" />} title="No clients registered"
                      hint="Register an OAuth2/OIDC client to let an application sign users in through this IdP." />
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
                      {url ? (
                        <a href={url} target="_blank" rel="noreferrer" title={`Open ${url}`}
                           className="inline-flex items-center gap-1.5 text-primary hover:underline">
                          {c.clientId} <ExternalLink className="size-3.5" />
                        </a>
                      ) : c.clientId}
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
                      <Button variant="ghost" size="icon" onClick={() => remove(c)}
                              className="text-muted-foreground hover:text-destructive"><Trash2 /></Button>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </DataList>

      <p className="mt-4 max-w-3xl text-sm text-muted-foreground">
        Keycloak: add this server as an OIDC Identity Provider using discovery{" "}
        <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">{location.origin}/.well-known/openid-configuration</code>, the client_id/secret created here,
        and set the redirect URI above to Keycloak's broker endpoint
        (<code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">.../realms/&lt;realm&gt;/broker/&lt;alias&gt;/endpoint</code>).
      </p>
    </>
  );
}
