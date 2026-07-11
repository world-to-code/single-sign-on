import { useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
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
  backchannelLogoutUri: "",
  backchannelLogoutSessionRequired: true,
};
type Form = typeof empty;

type Tab = "general" | "tokens" | "advanced";

/**
 * Okta-style full-page registration for an OAuth2 / OIDC client (route `clients/new`). On success the
 * one-time client secret is shown on this page (not carried back through navigation) before returning.
 */
export default function ClientCreate() {
  const { t } = useTranslation("console");
  const TABS = [
    { key: "general" as const, label: t("clientCreateTabGeneral") },
    { key: "tokens" as const, label: t("clientCreateTabTokens") },
    { key: "advanced" as const, label: t("clientCreateTabAdvanced") },
  ];
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
    if (!form.clientId.trim()) { setError(t("clientCreateIdRequired")); setTab("general"); return; }
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
        backchannelLogoutUri: form.backchannelLogoutUri || null,
        backchannelLogoutSessionRequired: form.backchannelLogoutSessionRequired,
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
          <Link to="/admin/clients" className="hover:text-foreground">{t("clientCreateBack")}</Link>
          <ChevronRight className="size-3.5 opacity-60" />
          <span className="font-medium text-foreground">{created.clientId}</span>
        </nav>
        <Alert variant="success" className="mb-6">
          <AlertTitle>{t("clientCreateCreatedTitle", { clientId: created.clientId })}</AlertTitle>
          <AlertDescription>
            {created.clientSecret ? (
              <>
                <p className="mb-2 text-muted-foreground">{t("clientCreateSecretOnce")}</p>
                <code className="block break-all rounded-md bg-muted px-3 py-2 font-mono text-xs text-foreground">
                  {created.clientSecret}
                </code>
              </>
            ) : (
              <p>{t("clientCreatePublicClient")}</p>
            )}
          </AlertDescription>
        </Alert>
        <Button onClick={() => navigate("/admin/clients")}>{t("clientCreateBackToClients")}</Button>
      </div>
    );
  }

  return (
    <EditorPage<Tab>
      backTo="/admin/clients" backLabel={t("clientCreateBack")} crumb={t("clientCreateCrumb")}
      title={t("clientCreateTitle")}
      description={t("clientCreateDescription")}
      tabs={TABS} activeTab={tab} onTab={setTab}
      error={error} formId="client-form" onSubmit={submit} busy={busy} submitLabel={t("clientCreateSubmit")}
      onCancel={() => navigate("/admin/clients")}
    >
      {tab === "general" && (
        <>
          <SettingsSection title={t("clientCreateIdentity")} description={t("clientCreateIdentityDesc")}>
            <Field label={t("clientCreateClientId")}>
              <Input value={form.clientId} onChange={(e) => set({ clientId: e.target.value })} placeholder="my-app" />
            </Field>
            <Field label={t("clientCreateClientName")}>
              <Input value={form.clientName} onChange={(e) => set({ clientName: e.target.value })} placeholder="My Application" />
            </Field>
          </SettingsSection>

          <SettingsSection title={t("clientCreateRedirects")} description={t("clientCreateRedirectsDesc")}>
            <Field label={t("clientCreateRedirectUris")} hint={t("clientCreateSpaceComma")}>
              <Textarea rows={2} value={form.redirectUris} onChange={(e) => set({ redirectUris: e.target.value })}
                        placeholder="https://app.example.com/callback" />
            </Field>
            <Field label={t("clientCreateInitiateLogin")}
                   hint={t("clientCreateInitiateLoginHint")}>
              <Input value={form.initiateLoginUri} onChange={(e) => set({ initiateLoginUri: e.target.value })}
                     placeholder="https://app.example.com/login/oidc/start" />
            </Field>
            <Field label={t("clientCreatePostLogout")} hint={t("clientCreateSpaceComma")}>
              <Textarea rows={1} value={form.postLogoutRedirectUris} onChange={(e) => set({ postLogoutRedirectUris: e.target.value })} />
            </Field>
            <Field label={t("clientCreateBackchannel")}
                   hint={t("clientCreateBackchannelHint")}>
              <Input value={form.backchannelLogoutUri} onChange={(e) => set({ backchannelLogoutUri: e.target.value })}
                     placeholder="https://app.example.com/logout/backchannel" />
            </Field>
            <Toggle label={t("clientCreateRequireSid")}
                    hint={t("clientCreateRequireSidHint")}
                    checked={form.backchannelLogoutSessionRequired} onChange={(v) => set({ backchannelLogoutSessionRequired: v })} />
          </SettingsSection>

          <SettingsSection title={t("clientCreateScopesGrants")} description={t("clientCreateScopesGrantsDesc")}>
            <CheckboxCards label={t("clientCreateScopes")} options={SCOPE_OPTIONS} selected={form.scopes}
                           onToggle={(v) => set({ scopes: toggleIn(form.scopes, v) })} />
            <Field label={t("clientCreateAdditionalScopes")} hint={t("clientCreateAdditionalScopesHint")}>
              <Input value={form.customScopes} onChange={(e) => set({ customScopes: e.target.value })}
                     placeholder="e.g. orders:read billing:write" />
            </Field>
            <CheckboxCards label={t("clientCreateGrantTypes")} options={GRANT_TYPE_OPTIONS} selected={form.grantTypes}
                           onToggle={(v) => set({ grantTypes: toggleIn(form.grantTypes, v) })} />
          </SettingsSection>

          <SettingsSection title={t("clientCreateClientType")} description={t("clientCreateClientTypeDesc")}>
            <Toggle label={t("clientCreatePublicClientLabel")} hint={t("clientCreatePublicClientHint")} checked={form.publicClient} onChange={(v) => set({ publicClient: v })} />
            <Toggle label={t("clientCreateRequireConsent")} checked={form.requireConsent} onChange={(v) => set({ requireConsent: v })} />
            <Toggle label={t("clientCreateRequirePkce")} checked={form.requireProofKey} onChange={(v) => set({ requireProofKey: v })} />
          </SettingsSection>
        </>
      )}

      {tab === "tokens" && (
        <>
          <SettingsSection title={t("clientCreateTokenLifetimes")} description={t("clientCreateTokenLifetimesDesc")}>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label={t("clientCreateAccessTtl")}>
                <Input value={form.accessTokenMinutes} onChange={(e) => set({ accessTokenMinutes: e.target.value })} inputMode="numeric" />
              </Field>
              <Field label={t("clientCreateRefreshTtl")}>
                <Input value={form.refreshTokenDays} onChange={(e) => set({ refreshTokenDays: e.target.value })} inputMode="numeric" />
              </Field>
              <Field label={t("clientCreateAuthCodeTtl")}>
                <Input value={form.authorizationCodeMinutes} onChange={(e) => set({ authorizationCodeMinutes: e.target.value })} inputMode="numeric" />
              </Field>
              <Field label={t("clientCreateDeviceCodeTtl")}>
                <Input value={form.deviceCodeMinutes} onChange={(e) => set({ deviceCodeMinutes: e.target.value })} inputMode="numeric" />
              </Field>
            </div>
          </SettingsSection>

          <SettingsSection title={t("clientCreateTokenFormat")} description={t("clientCreateTokenFormatDesc")}>
            <Field label={t("clientCreateAccessFormat")}>
              <Select value={form.accessTokenFormat} onChange={(e) => set({ accessTokenFormat: e.target.value })}>
                <option>SELF_CONTAINED</option><option>REFERENCE</option>
              </Select>
            </Field>
            <Field label={t("clientCreateIdTokenSig")}>
              <Select value={form.idTokenSignatureAlgorithm} onChange={(e) => set({ idTokenSignatureAlgorithm: e.target.value })}>
                {ID_TOKEN_SIG_ALGS.map((alg) => <option key={alg} value={alg}>{alg}</option>)}
              </Select>
            </Field>
            <Field label={t("clientCreateSecretExpiry")} hint={t("clientCreateBlankNever")}>
              <Input value={form.clientSecretDays} onChange={(e) => set({ clientSecretDays: e.target.value })} inputMode="numeric" />
            </Field>
          </SettingsSection>
        </>
      )}

      {tab === "advanced" && (
        <SettingsSection title={t("clientCreateClientAuth")}
                         description={t("clientCreateClientAuthDesc")}>
          <CheckboxCards label={t("clientCreateAuthMethods")} columns={1} options={AUTH_METHOD_OPTIONS}
                         selected={form.clientAuthenticationMethods}
                         onToggle={(v) => set({ clientAuthenticationMethods: toggleIn(form.clientAuthenticationMethods, v) })} />
          <Field label={t("clientCreateTokenAuthSig")}
                 hint={t("clientCreateTokenAuthSigHint")}>
            <Select value={form.tokenEndpointAuthSigningAlgorithm} onChange={(e) => set({ tokenEndpointAuthSigningAlgorithm: e.target.value })}>
              <option value="">{t("clientCreateDefault")}</option>
              {TOKEN_ENDPOINT_SIG_ALGS.map((alg) => <option key={alg} value={alg}>{alg}</option>)}
            </Select>
          </Field>
          <Field label={t("clientCreateJwkSetUrl")}>
            <Input value={form.jwkSetUrl} onChange={(e) => set({ jwkSetUrl: e.target.value })}
                   placeholder="https://client/.well-known/jwks.json" />
          </Field>
          <Field label={t("clientCreateX509Dn")}>
            <Input value={form.x509SubjectDn} onChange={(e) => set({ x509SubjectDn: e.target.value })} />
          </Field>
          <Toggle label={t("clientCreateCertBound")} checked={form.x509BoundAccessTokens}
                  onChange={(v) => set({ x509BoundAccessTokens: v })} />
        </SettingsSection>
      )}
    </EditorPage>
  );
}
