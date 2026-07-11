import { useEffect, useState } from "react";
import { Trans, useTranslation } from "react-i18next";
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

// Reusable inline-markup components for the developer-reference prose (Trans placeholders).
const codeXs = <code className="font-mono text-xs" />;
const codeMono = <code className="font-mono" />;

export default function ClientDetail() {
  const { t } = useTranslation("console");
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
        <ArrowLeft className="size-4" /> {t("clientDetailBack")}
      </Link>
      <PageHeader
        title={client ? (client.clientName || client.clientId) : t("clientDetailFallbackTitle")}
        description={t("clientDetailDescription")}
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {client && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>{t("clientDetailConfigTitle")}</CardTitle>
              <CardDescription>{t("clientDetailConfigDesc")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <CopyField label={t("clientDetailClientId")} value={client.clientId} />
              <div className="grid gap-1.5">
                <p className="text-sm font-medium">{t("clientDetailRedirectUris")}</p>
                {redirectUris.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{t("clientDetailNoRedirects")}</p>
                ) : (
                  <div className="space-y-2">
                    {redirectUris.map((uri) => <CopyField key={uri} label="" value={uri} />)}
                  </div>
                )}
              </div>
              <div className="grid gap-1.5">
                <p className="text-sm font-medium">{t("clientDetailScopes")}</p>
                <div className="flex flex-wrap gap-1">
                  {scopes.map((s) => <Badge key={s} variant="secondary">{s}</Badge>)}
                </div>
              </div>
              <div className="grid gap-1.5">
                <p className="text-sm font-medium">{t("clientDetailGrantTypes")}</p>
                <div className="flex flex-wrap gap-1">
                  {grantTypes.map((g) => <Badge key={g} variant="muted" className="font-mono text-xs">{g}</Badge>)}
                </div>
              </div>
              {client.initiateLoginUri && <CopyField label={t("clientDetailInitiateLogin")} value={client.initiateLoginUri} />}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>{t("clientDetailEndpointsTitle")}</CardTitle>
              <CardDescription>
                {t("clientDetailEndpointsDesc")}
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {!discovery ? (
                <p className="text-sm text-muted-foreground">{t("clientDetailLoadingMeta")}</p>
              ) : (
                <>
                  <CopyField label={t("clientDetailIssuer")} value={discovery.issuer} hint={t("clientDetailIssuerHint")} />
                  <CopyField label={t("clientDetailDiscoveryDoc")} value={discoveryUrl(discovery.issuer)}
                             hint={t("clientDetailDiscoveryHint")} />
                  <CopyField label={t("clientDetailAuthEndpoint")} value={discovery.authorization_endpoint} />
                  <CopyField label={t("clientDetailTokenEndpoint")} value={discovery.token_endpoint} />
                  <CopyField label={t("clientDetailUserinfoEndpoint")} value={discovery.userinfo_endpoint} />
                  <CopyField label={t("clientDetailJwksUri")} value={discovery.jwks_uri} hint={t("clientDetailJwksHint")} />
                  {discovery.end_session_endpoint && <CopyField label={t("clientDetailEndSession")} value={discovery.end_session_endpoint} />}
                </>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>{t("clientDetailHowToConnect")}</CardTitle>
              <CardDescription>{t("clientDetailHowToConnectDesc")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2 text-sm text-muted-foreground">
              <p><Trans t={t} i18nKey="clientDetailStep1" components={[<strong key="0" />, codeXs, codeXs, codeXs, codeXs, codeXs]} /></p>
              <p><Trans t={t} i18nKey="clientDetailStep2" components={[codeXs, codeXs]} /></p>
              <p><Trans t={t} i18nKey="clientDetailStep3" components={[<strong key="0" />, codeXs, codeXs, codeXs]} /></p>
              <p><Trans t={t} i18nKey="clientDetailStep4" components={[<strong key="0" />, <strong key="1" />]} /></p>
              {redirectUris[0] && (
                <p className="pt-1">
                  <Trans t={t} i18nKey="clientDetailSetRedirect" values={{ uri: redirectUris[0] }}
                         components={[<a key="0" href={redirectUris[0]} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline" />]} />
                  <ExternalLink className="ml-1 inline size-3" />
                </p>
              )}
            </CardContent>
          </Card>

          {discovery && (
            <Card>
              <CardHeader>
                <CardTitle>{t("clientDetailQuickStart")}</CardTitle>
                <CardDescription>
                  <Trans t={t} i18nKey="clientDetailQuickStartDesc" components={[codeXs, codeXs]} />
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-5">
                <div className="space-y-2">
                  <CodeBlock
                    wrap
                    label={t("clientDetailAuthReqLabel")}
                    code={`${discovery.authorization_endpoint}?response_type=code`
                      + `&client_id=${encodeURIComponent(client.clientId)}`
                      + `&redirect_uri=${encodeURIComponent(redirect)}`
                      + `&scope=${encodeURIComponent(scopeParam)}`
                      + `&state=<STATE>&nonce=<NONCE>`
                      + `&code_challenge=<PKCE_CHALLENGE>&code_challenge_method=S256`}
                  />
                  <ul className="space-y-0.5 text-xs text-muted-foreground">
                    <li><Trans t={t} i18nKey="clientDetailUl1" components={[codeMono]} /></li>
                    <li><Trans t={t} i18nKey="clientDetailUl2" components={[codeMono, codeMono]} /></li>
                    <li><Trans t={t} i18nKey="clientDetailUl3" components={[codeMono, codeMono]} /></li>
                    <li><Trans t={t} i18nKey="clientDetailUl4" components={[codeMono, codeMono]} /></li>
                    <li><Trans t={t} i18nKey="clientDetailUl5" components={[codeMono, codeMono, codeMono]} /></li>
                  </ul>
                </div>
                <CodeBlock
                  label={t("clientDetailExchangeLabel")}
                  code={`curl -X POST '${discovery.token_endpoint}' \\\n`
                    + `  -d grant_type=authorization_code \\\n`
                    + `  -d code=<AUTH_CODE> \\\n`
                    + `  -d redirect_uri='${redirect}' \\\n`
                    + `  -d client_id='${client.clientId}' \\\n`
                    + `  -d code_verifier=<PKCE_VERIFIER>\n`
                    + `# confidential client: also send the secret, e.g. add  -u '${client.clientId}:<CLIENT_SECRET>'`}
                  hint={t("clientDetailExchangeHint")}
                />
                <CodeBlock
                  label={t("clientDetailLibLabel")}
                  code={`spring.security.oauth2.client.provider.mysso.issuer-uri=${discovery.issuer}\n`
                    + `spring.security.oauth2.client.registration.mysso.client-id=${client.clientId}\n`
                    + `spring.security.oauth2.client.registration.mysso.client-secret=<CLIENT_SECRET>\n`
                    + `spring.security.oauth2.client.registration.mysso.scope=${scopeParam.replace(/ /g, ",")}\n`
                    + `spring.security.oauth2.client.registration.mysso.redirect-uri=${redirect}`}
                  hint={t("clientDetailLibHint")}
                />
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </>
  );
}
