import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { apiPost, errorMessage } from "../api";
import { EditorPage } from "@/components/EditorPage";
import { SettingsSection } from "@/components/SettingsSection";
import { Field, Toggle } from "@/components/form/fields";
import { CheckboxCards } from "@/components/form/CheckboxCards";
import {
  AUTH_METHOD_OPTIONS, GRANT_TYPE_OPTIONS, ID_TOKEN_SIG_ALGS, SCOPE_OPTIONS, TOKEN_ENDPOINT_SIG_ALGS,
} from "@/lib/oidcOptions";
import { tokens } from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

const empty = {
  clientId: "",
  clientName: "",
  redirectUris: "",
  postLogoutRedirectUris: "",
  scopes: ["openid", "profile", "email"] as string[],
  customScopes: "",
  grantTypes: ["authorization_code", "refresh_token"] as string[],
  clientAuthenticationMethods: ["client_secret_basic", "client_secret_post"] as string[],
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
  initiateLoginUri: "",
};
type Form = typeof empty;

type Tab = "general" | "tokens" | "advanced";
const TABS = [
  { key: "general" as const, label: "General" },
  { key: "tokens" as const, label: "Tokens" },
  { key: "advanced" as const, label: "Advanced security" },
];

/**
 * Okta-style full-page registration for an OAuth2 / OIDC client (route `clients/new`). On success the
 * one-time client secret is shown on this page (not carried back through navigation) before returning.
 */
export default function ClientCreate() {
  const navigate = useNavigate();
  const [form, setForm] = useState<Form>({ ...empty });
  const [tab, setTab] = useState<Tab>("general");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [created, setCreated] = useState<{ clientId: string; clientSecret: string | null } | null>(null);

  const set = (patch: Partial<Form>) => setForm((f) => ({ ...f, ...patch }));
  const toggleIn = (list: string[], value: string) =>
    list.includes(value) ? list.filter((v) => v !== value) : [...list, value];

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.clientId.trim()) { setError("Client ID is required."); setTab("general"); return; }
    setError(null); setBusy(true);
    const num = (v: string): number | null => (v.trim() ? Number(v) : null);
    try {
      const result = await apiPost<{ clientId: string; clientSecret: string | null }>("/api/admin/clients", {
        clientId: form.clientId,
        clientName: form.clientName || null,
        redirectUris: tokens(form.redirectUris),
        postLogoutRedirectUris: tokens(form.postLogoutRedirectUris),
        scopes: [...new Set([...form.scopes, ...tokens(form.customScopes)])],
        grantTypes: form.grantTypes,
        clientAuthenticationMethods: form.clientAuthenticationMethods,
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
        initiateLoginUri: form.initiateLoginUri || null,
      });
      setCreated(result);
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

  // Success view: the one-time secret is shown here and copied before leaving.
  if (created) {
    return (
      <div>
        <nav className="mb-5 flex items-center gap-1.5 text-sm text-muted-foreground">
          <Link to="/admin/clients" className="hover:text-foreground">OAuth2 / OIDC Clients</Link>
          <ChevronRight className="size-3.5 opacity-60" />
          <span className="font-medium text-foreground">{created.clientId}</span>
        </nav>
        <Alert variant="success" className="mb-6">
          <AlertTitle>Client "{created.clientId}" created</AlertTitle>
          <AlertDescription>
            {created.clientSecret ? (
              <>
                <p className="mb-2 text-muted-foreground">Secret (shown once — copy now):</p>
                <code className="block break-all rounded-md bg-muted px-3 py-2 font-mono text-xs text-foreground">
                  {created.clientSecret}
                </code>
              </>
            ) : (
              <p>Public client (PKCE, no secret).</p>
            )}
          </AlertDescription>
        </Alert>
        <Button onClick={() => navigate("/admin/clients")}>Back to clients</Button>
      </div>
    );
  }

  return (
    <EditorPage<Tab>
      backTo="/admin/clients" backLabel="OAuth2 / OIDC Clients" crumb="New client"
      title="Register an OAuth2 / OIDC client"
      description="Confidential clients receive a secret shown once; public clients use PKCE."
      tabs={TABS} activeTab={tab} onTab={setTab}
      error={error} formId="client-form" onSubmit={submit} busy={busy} submitLabel="Create client"
      onCancel={() => navigate("/admin/clients")}
    >
      {tab === "general" && (
        <>
          <SettingsSection title="Identity" description="How the application identifies itself to this IdP.">
            <Field label="Client ID">
              <Input value={form.clientId} onChange={(e) => set({ clientId: e.target.value })} placeholder="my-app" />
            </Field>
            <Field label="Client name">
              <Input value={form.clientName} onChange={(e) => set({ clientName: e.target.value })} placeholder="My Application" />
            </Field>
          </SettingsSection>

          <SettingsSection title="Redirects" description="Where the IdP may return the user after authorization and logout.">
            <Field label="Redirect URIs" hint="Space or comma separated.">
              <Textarea rows={2} value={form.redirectUris} onChange={(e) => set({ redirectUris: e.target.value })}
                        placeholder="https://app.example.com/callback" />
            </Field>
            <Field label="Initiate login URI"
                   hint="OIDC third-party-initiated login: the portal 'launch' redirects here (with ?iss=), then the app starts its own OIDC flow. Optional — falls back to the app origin.">
              <Input value={form.initiateLoginUri} onChange={(e) => set({ initiateLoginUri: e.target.value })}
                     placeholder="https://app.example.com/login/oidc/start" />
            </Field>
            <Field label="Post-logout redirect URIs" hint="Space or comma separated.">
              <Textarea rows={1} value={form.postLogoutRedirectUris} onChange={(e) => set({ postLogoutRedirectUris: e.target.value })} />
            </Field>
          </SettingsSection>

          <SettingsSection title="Scopes & grants" description="What the client may request and how it obtains tokens.">
            <CheckboxCards label="Scopes" options={SCOPE_OPTIONS} selected={form.scopes}
                           onToggle={(v) => set({ scopes: toggleIn(form.scopes, v) })} />
            <Field label="Additional scopes" hint="Custom/API scopes beyond the standard ones — space or comma separated.">
              <Input value={form.customScopes} onChange={(e) => set({ customScopes: e.target.value })}
                     placeholder="e.g. orders:read billing:write" />
            </Field>
            <CheckboxCards label="Grant types" options={GRANT_TYPE_OPTIONS} selected={form.grantTypes}
                           onToggle={(v) => set({ grantTypes: toggleIn(form.grantTypes, v) })} />
          </SettingsSection>

          <SettingsSection title="Client type" description="Whether the client holds a secret and how consent and PKCE are enforced.">
            <Toggle label="Public client" hint="PKCE, no secret." checked={form.publicClient} onChange={(v) => set({ publicClient: v })} />
            <Toggle label="Require consent" checked={form.requireConsent} onChange={(v) => set({ requireConsent: v })} />
            <Toggle label="Require PKCE (confidential)" checked={form.requireProofKey} onChange={(v) => set({ requireProofKey: v })} />
          </SettingsSection>
        </>
      )}

      {tab === "tokens" && (
        <>
          <SettingsSection title="Token lifetimes" description="How long the tokens this client is issued remain valid.">
            <div className="grid gap-5 sm:grid-cols-2">
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
          </SettingsSection>

          <SettingsSection title="Token format" description="How access and ID tokens are formatted and how long the secret lives.">
            <Field label="Access token format">
              <Select value={form.accessTokenFormat} onChange={(e) => set({ accessTokenFormat: e.target.value })}>
                <option>SELF_CONTAINED</option><option>REFERENCE</option>
              </Select>
            </Field>
            <Field label="ID token signature algorithm">
              <Select value={form.idTokenSignatureAlgorithm} onChange={(e) => set({ idTokenSignatureAlgorithm: e.target.value })}>
                {ID_TOKEN_SIG_ALGS.map((alg) => <option key={alg} value={alg}>{alg}</option>)}
              </Select>
            </Field>
            <Field label="Client secret expiry (days)" hint="Blank = never.">
              <Input value={form.clientSecretDays} onChange={(e) => set({ clientSecretDays: e.target.value })} inputMode="numeric" />
            </Field>
          </SettingsSection>
        </>
      )}

      {tab === "advanced" && (
        <SettingsSection title="Client authentication"
                         description="How a confidential client proves its identity at the token endpoint (JWT, mTLS).">
          <CheckboxCards label="Client authentication methods" columns={1} options={AUTH_METHOD_OPTIONS}
                         selected={form.clientAuthenticationMethods}
                         onToggle={(v) => set({ clientAuthenticationMethods: toggleIn(form.clientAuthenticationMethods, v) })} />
          <Field label="Token-endpoint auth signing alg (JWT client auth)"
                 hint="For private_key_jwt (RS/ES/PS) or client_secret_jwt (HS). Leave as Default otherwise.">
            <Select value={form.tokenEndpointAuthSigningAlgorithm} onChange={(e) => set({ tokenEndpointAuthSigningAlgorithm: e.target.value })}>
              <option value="">Default</option>
              {TOKEN_ENDPOINT_SIG_ALGS.map((alg) => <option key={alg} value={alg}>{alg}</option>)}
            </Select>
          </Field>
          <Field label="JWK Set URL (private_key_jwt)">
            <Input value={form.jwkSetUrl} onChange={(e) => set({ jwkSetUrl: e.target.value })}
                   placeholder="https://client/.well-known/jwks.json" />
          </Field>
          <Field label="X.509 subject DN (tls_client_auth)">
            <Input value={form.x509SubjectDn} onChange={(e) => set({ x509SubjectDn: e.target.value })} />
          </Field>
          <Toggle label="Certificate-bound (mTLS) access tokens" checked={form.x509BoundAccessTokens}
                  onChange={(v) => set({ x509BoundAccessTokens: v })} />
        </SettingsSection>
      )}
    </EditorPage>
  );
}
