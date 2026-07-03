import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, ExternalLink } from "lucide-react";
import { getClient, getDiscovery, discoveryUrl, type ClientRow, type OidcDiscovery } from "@/clients";
import { errorMessage } from "@/api";
import { tokens } from "@/lib/utils";
import { PageHeader } from "@/components/PageHeader";
import { CopyField } from "@/components/CopyField";
import { CodeBlock } from "@/components/CodeBlock";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function ClientDetail() {
  const { id = "" } = useParams();
  const [client, setClient] = useState<ClientRow | null>(null);
  const [discovery, setDiscovery] = useState<OidcDiscovery | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getClient(id).then(setClient).catch((e) => setError(errorMessage(e)));
    getDiscovery().then(setDiscovery).catch((e) => setError(errorMessage(e)));
  }, [id]);

  const redirectUris = client ? tokens(client.redirectUris) : [];
  const scopes = client ? tokens(client.scopes) : [];
  const grantTypes = client ? tokens(client.grantTypes) : [];
  const redirect = redirectUris[0] ?? "https://your-app.example/callback";
  const scopeParam = (scopes.length ? scopes : ["openid"]).join(" ");

  return (
    <>
      <Link to="/admin/clients" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> Back to clients
      </Link>
      <PageHeader
        title={client ? (client.clientName || client.clientId) : "Client"}
        description="OIDC integration details — endpoint URLs and how an application connects to this provider."
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {client && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>Client configuration</CardTitle>
              <CardDescription>What the application registers with this IdP. The client secret is shown only once, at creation.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <CopyField label="Client ID" value={client.clientId} />
              <div className="grid gap-1.5">
                <p className="text-sm font-medium">Redirect URIs</p>
                {redirectUris.length === 0 ? (
                  <p className="text-sm text-muted-foreground">None configured — the app cannot complete a login until one is added.</p>
                ) : (
                  <div className="space-y-2">
                    {redirectUris.map((uri) => <CopyField key={uri} label="" value={uri} />)}
                  </div>
                )}
              </div>
              <div className="grid gap-1.5">
                <p className="text-sm font-medium">Scopes</p>
                <div className="flex flex-wrap gap-1">
                  {scopes.map((s) => <Badge key={s} variant="secondary">{s}</Badge>)}
                </div>
              </div>
              <div className="grid gap-1.5">
                <p className="text-sm font-medium">Grant types</p>
                <div className="flex flex-wrap gap-1">
                  {grantTypes.map((g) => <Badge key={g} variant="muted" className="font-mono text-xs">{g}</Badge>)}
                </div>
              </div>
              {client.initiateLoginUri && <CopyField label="Initiate-login URI (third-party login)" value={client.initiateLoginUri} />}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>OpenID Provider endpoints</CardTitle>
              <CardDescription>
                Point the application's OIDC client at these. Most libraries only need the issuer or the discovery URL —
                they read the rest automatically.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {!discovery ? (
                <p className="text-sm text-muted-foreground">Loading provider metadata…</p>
              ) : (
                <>
                  <CopyField label="Issuer" value={discovery.issuer} hint="The base identifier; many clients configure only this." />
                  <CopyField label="Discovery document" value={discoveryUrl(discovery.issuer)}
                             hint="OpenID auto-configuration — the one URL to paste into a discovery-based client." />
                  <CopyField label="Authorization endpoint" value={discovery.authorization_endpoint} />
                  <CopyField label="Token endpoint" value={discovery.token_endpoint} />
                  <CopyField label="UserInfo endpoint" value={discovery.userinfo_endpoint} />
                  <CopyField label="JWKS URI" value={discovery.jwks_uri} hint="Public keys for verifying ID/access token signatures." />
                  {discovery.end_session_endpoint && <CopyField label="End-session (logout) endpoint" value={discovery.end_session_endpoint} />}
                </>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>How to connect</CardTitle>
              <CardDescription>Authorization Code flow with PKCE — the standard for web, mobile, and SPA clients.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2 text-sm text-muted-foreground">
              <p>1. The app sends the user to the <strong>authorization endpoint</strong> with its <code className="font-mono text-xs">client_id</code>, a registered <code className="font-mono text-xs">redirect_uri</code>, <code className="font-mono text-xs">scope=openid …</code>, <code className="font-mono text-xs">response_type=code</code>, and a PKCE <code className="font-mono text-xs">code_challenge</code>.</p>
              <p>2. The user authenticates here (password + MFA / passkey); this IdP redirects back to the <code className="font-mono text-xs">redirect_uri</code> with a one-time <code className="font-mono text-xs">code</code>.</p>
              <p>3. The app exchanges the code at the <strong>token endpoint</strong> (with its <code className="font-mono text-xs">code_verifier</code>, plus the client secret for a confidential client) for an <code className="font-mono text-xs">id_token</code> + <code className="font-mono text-xs">access_token</code>.</p>
              <p>4. The app calls the <strong>UserInfo endpoint</strong> with the access token for profile claims, and verifies token signatures against the <strong>JWKS URI</strong>.</p>
              {redirectUris[0] && (
                <p className="pt-1">
                  Set the app's redirect/callback URL to a registered value, e.g.{" "}
                  <a href={redirectUris[0]} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">
                    {redirectUris[0]} <ExternalLink className="size-3" />
                  </a>.
                </p>
              )}
            </CardContent>
          </Card>

          {discovery && (
            <Card>
              <CardHeader>
                <CardTitle>Quick start</CardTitle>
                <CardDescription>
                  A standard OIDC library pointed at the issuer/discovery URL builds these requests for you —
                  including <code className="font-mono text-xs">state</code>, <code className="font-mono text-xs">nonce</code> and PKCE.
                  The snippets below (prefilled with this client) are for reference and manual testing.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-5">
                <div className="space-y-2">
                  <CodeBlock
                    wrap
                    label="1. Authorization request (redirect the user here)"
                    code={`${discovery.authorization_endpoint}?response_type=code`
                      + `&client_id=${encodeURIComponent(client.clientId)}`
                      + `&redirect_uri=${encodeURIComponent(redirect)}`
                      + `&scope=${encodeURIComponent(scopeParam)}`
                      + `&state=<STATE>&nonce=<NONCE>`
                      + `&code_challenge=<PKCE_CHALLENGE>&code_challenge_method=S256`}
                  />
                  <ul className="space-y-0.5 text-xs text-muted-foreground">
                    <li><code className="font-mono">response_type=code</code> — Authorization Code flow.</li>
                    <li><code className="font-mono">client_id</code> / <code className="font-mono">redirect_uri</code> — this client and one of its registered URIs (must match exactly).</li>
                    <li><code className="font-mono">scope</code> — must include <code className="font-mono">openid</code> to get an ID token.</li>
                    <li><code className="font-mono">state</code> / <code className="font-mono">nonce</code> — random per request (CSRF / replay protection); verified on return.</li>
                    <li><code className="font-mono">code_challenge</code> (+ <code className="font-mono">method=S256</code>) — PKCE; the library keeps the matching <code className="font-mono">code_verifier</code> for step&nbsp;2.</li>
                  </ul>
                </div>
                <CodeBlock
                  label="2. Exchange the returned code for tokens (server-side)"
                  code={`curl -X POST '${discovery.token_endpoint}' \\\n`
                    + `  -d grant_type=authorization_code \\\n`
                    + `  -d code=<AUTH_CODE> \\\n`
                    + `  -d redirect_uri='${redirect}' \\\n`
                    + `  -d client_id='${client.clientId}' \\\n`
                    + `  -d code_verifier=<PKCE_VERIFIER>\n`
                    + `# confidential client: also send the secret, e.g. add  -u '${client.clientId}:<CLIENT_SECRET>'`}
                  hint="Returns an id_token + access_token. Verify signatures against the JWKS URI above."
                />
                <CodeBlock
                  label="Or configure a library from discovery (Spring Boot example)"
                  code={`spring.security.oauth2.client.provider.mysso.issuer-uri=${discovery.issuer}\n`
                    + `spring.security.oauth2.client.registration.mysso.client-id=${client.clientId}\n`
                    + `spring.security.oauth2.client.registration.mysso.client-secret=<CLIENT_SECRET>\n`
                    + `spring.security.oauth2.client.registration.mysso.scope=${scopeParam.replace(/ /g, ",")}\n`
                    + `spring.security.oauth2.client.registration.mysso.redirect-uri=${redirect}`}
                  hint="Any OIDC-certified library works the same way — give it the issuer, client id/secret, redirect URI and scopes."
                />
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </>
  );
}
