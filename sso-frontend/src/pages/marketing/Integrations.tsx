import { useTranslation } from "react-i18next";
import { Blocks, Network, Plug, RefreshCw } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Section, getStarted } from "@/components/marketing/MarketingLayout";
import { cn } from "@/lib/utils";

type MKey = keyof (typeof import("@/i18n/en/marketing"))["marketing"];

/** Integrations page ("/integrations"): a developer-flavored walkthrough — each protocol paired with a
 *  real, tokenized config/code block, then a connection stepper and an app catalog. */
export default function Integrations() {
  const { t } = useTranslation("marketing");
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <div className="relative mx-auto max-w-3xl px-4 py-20 text-center sm:px-6 sm:py-24">
          <Badge variant="muted" className="mb-5">{t("integrationsHeroBadge")}</Badge>
          <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">{t("integrationsHeroTitle")}</h1>
          <p className="mx-auto mt-5 max-w-xl text-pretty text-lg text-muted-foreground">{t("integrationsHeroBody")}</p>
          <div className="mt-8">
            <Button size="lg" onClick={getStarted}>{t("integrationsHeroCta")}</Button>
          </div>
        </div>
      </section>

      <Section>
        <div className="mb-12 max-w-2xl">
          <Badge variant="muted" className="mb-3">{t("integrationsProtocolsBadge")}</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("integrationsProtocolsTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("integrationsProtocolsBody")}</p>
        </div>
        <div className="space-y-16">
          {PROTOCOLS.map((p, i) => (
            <div key={p.name} className="grid items-center gap-8 lg:grid-cols-2 lg:gap-12">
              <div className={cn(i % 2 === 1 && "lg:order-2")}>
                <div className="flex items-center gap-3">
                  <div className="flex size-11 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                    <p.icon className="size-5" />
                  </div>
                  <div>
                    <h3 className="text-xl font-semibold">{p.name}</h3>
                    <span className="font-mono text-xs text-muted-foreground">{p.tag}</span>
                  </div>
                </div>
                <p className="mt-4 text-muted-foreground">{t(p.body)}</p>
                <ul className="mt-4 flex flex-wrap gap-2">
                  {p.chips.map((c) => (
                    <li key={c} className="rounded-full border bg-card px-3 py-1 font-mono text-xs text-muted-foreground">{c}</li>
                  ))}
                </ul>
              </div>
              <div className={cn(i % 2 === 1 && "lg:order-1")}>
                <CodeBlock title={p.code.title} lines={p.code.lines} />
              </div>
            </div>
          ))}
        </div>
      </Section>

      <Section tone="muted">
        <div className="mb-10 max-w-2xl">
          <Badge variant="muted" className="mb-3">{t("integrationsFlowBadge")}</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("integrationsFlowTitle")}</h2>
        </div>
        <ol className="grid gap-6 md:grid-cols-3">
          {STEPS.map((s, i) => (
            <li key={s.title} className="relative">
              <div className="flex items-center gap-3">
                <span className="flex size-10 items-center justify-center rounded-lg bg-primary font-semibold text-primary-foreground">{i + 1}</span>
                {i < STEPS.length - 1 && <span className="hidden h-px flex-1 bg-border md:block" />}
              </div>
              <h3 className="mt-4 font-semibold">{t(s.title)}</h3>
              <p className="mt-1.5 text-sm text-muted-foreground">{t(s.body)}</p>
            </li>
          ))}
        </ol>
      </Section>

      <Section>
        <div className="mb-8 max-w-2xl">
          <Badge variant="muted" className="mb-3">{t("integrationsCatalogBadge")}</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("integrationsCatalogTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("integrationsCatalogBody")}</p>
        </div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {APPS.map((a) => (
            <div key={a} className="flex items-center gap-2.5 rounded-lg border bg-card p-3.5 text-sm font-medium">
              <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-accent text-accent-foreground">
                <Blocks className="size-4" />
              </span>
              {a}
            </div>
          ))}
        </div>
        <p className="mt-6 text-sm text-muted-foreground">{t("integrationsCatalogFooter")}</p>
      </Section>

      <Section tone="muted">
        <Badge variant="muted" className="mb-4">{t("integrationsEndpointsBadge")}</Badge>
        <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
          {t("integrationsEndpointsTitle")}
        </h2>
        <p className="mt-4 max-w-3xl text-pretty text-muted-foreground">{t("integrationsEndpointsBody")}</p>
        <div className="mt-8 overflow-x-auto rounded-xl border bg-card">
          <table className="w-full text-left text-sm">
            <thead className="border-b bg-muted/40">
              <tr>
                <th className="px-5 py-3 font-medium">{t("integrationsEndpointsColPath")}</th>
                <th className="px-5 py-3 font-medium">{t("integrationsEndpointsColPurpose")}</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {ENDPOINTS.map(([path, purpose]) => (
                <tr key={path}>
                  <td className="whitespace-nowrap px-5 py-3 font-mono text-xs">{path}</td>
                  <td className="px-5 py-3 text-muted-foreground">{t(purpose)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      <Section>
        <div className="mx-auto max-w-3xl">
          <Badge variant="muted" className="mb-4">{t("integrationsPitfallsBadge")}</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
            {t("integrationsPitfallsTitle")}
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">{t("integrationsPitfallsBody")}</p>
          <ol className="mt-8 space-y-5">
            {PITFALLS.map(([title, body], i) => (
              <li key={title} className="flex gap-4">
                <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border text-xs font-semibold">
                  {i + 1}
                </span>
                <div>
                  <h3 className="font-semibold">{t(title)}</h3>
                  <p className="mt-1 text-sm text-pretty text-muted-foreground">{t(body)}</p>
                </div>
              </li>
            ))}
          </ol>
        </div>
      </Section>

    </>
  );
}

/* ---------- Tokenized config/code block (a static mock — no real request) ---------- */

type Span = { t: string; c?: "k" | "s" | "c" };
type Line = Span[];

const spanClass: Record<NonNullable<Span["c"]>, string> = {
  k: "text-primary",
  s: "text-success",
  c: "text-band-fg/40",
};

function CodeBlock({ title, lines }: { title: string; lines: Line[] }) {
  return (
    <div className="overflow-hidden rounded-xl border border-white/10 bg-band shadow-lg">
      <div className="flex items-center gap-2 border-b border-white/10 px-4 py-2.5">
        <span className="size-2.5 rounded-full bg-destructive/50" />
        <span className="size-2.5 rounded-full bg-amber-400/50" />
        <span className="size-2.5 rounded-full bg-success/50" />
        <span className="ml-2 truncate font-mono text-xs text-band-fg/70">{title}</span>
      </div>
      <pre className="overflow-x-auto px-4 py-4 font-mono text-xs leading-relaxed text-band-fg">
        <code>
          {lines.map((line, i) => (
            <div key={i}>
              {line.length === 0
                ? " "
                : line.map((s, j) => (
                    <span key={j} className={s.c ? spanClass[s.c] : undefined}>{s.t}</span>
                  ))}
            </div>
          ))}
        </code>
      </pre>
    </div>
  );
}

const PROTOCOLS: {
  icon: LucideIcon; name: string; tag: string; body: MKey; chips: string[];
  code: { title: string; lines: Line[] };
}[] = [
  {
    icon: Plug, name: "OpenID Connect", tag: "OAuth 2.1-based", body: "integrationsOidcBody",
    chips: ["authorization_code", "PKCE", "id_token", "JWKS rotation"],
    code: {
      title: "GET /.well-known/openid-configuration",
      lines: [
        [{ t: "{" }],
        [{ t: "  " }, { t: '"issuer"', c: "k" }, { t: ": " }, { t: '"https://acme.mysso.com"', c: "s" }, { t: "," }],
        [{ t: "  " }, { t: '"authorization_endpoint"', c: "k" }, { t: ": " }, { t: '".../oauth2/authorize"', c: "s" }, { t: "," }],
        [{ t: "  " }, { t: '"token_endpoint"', c: "k" }, { t: ": " }, { t: '".../oauth2/token"', c: "s" }, { t: "," }],
        [{ t: "  " }, { t: '"jwks_uri"', c: "k" }, { t: ": " }, { t: '".../oauth2/jwks"', c: "s" }, { t: "," }],
        [{ t: "  " }, { t: '"userinfo_endpoint"', c: "k" }, { t: ": " }, { t: '".../userinfo"', c: "s" }],
        [{ t: "}" }],
      ],
    },
  },
  {
    icon: Network, name: "SAML 2.0", tag: "signed assertions", body: "integrationsSamlBody",
    chips: ["metadata", "signed response", "SLO", "X.509"],
    code: {
      title: "IdP metadata · acme.mysso.com",
      lines: [
        [{ t: "# per-tenant SAML 2.0 identity provider", c: "c" }],
        [{ t: "EntityID     ", c: "k" }, { t: "https://acme.mysso.com/saml2" }],
        [{ t: "SSO (POST)   ", c: "k" }, { t: "/saml2/authenticate" }],
        [{ t: "SLO          ", c: "k" }, { t: "/saml2/logout" }],
        [{ t: "NameID       ", c: "k" }, { t: "emailAddress" }],
        [{ t: "Signing      ", c: "k" }, { t: "X.509 · per-tenant key", c: "s" }],
      ],
    },
  },
  {
    icon: RefreshCw, name: "SCIM 2.0", tag: "provisioning", body: "integrationsScimBody",
    chips: ["/scim/v2/Users", "/scim/v2/Groups", "scoped token"],
    code: {
      title: "POST /scim/v2/Users",
      lines: [
        [{ t: "POST /scim/v2/Users", c: "k" }],
        [{ t: "Authorization: " }, { t: "Bearer <scoped-token>", c: "s" }],
        [{ t: "Content-Type: application/scim+json" }],
        [],
        [{ t: "{" }],
        [{ t: "  " }, { t: '"userName"', c: "k" }, { t: ": " }, { t: '"jordan@acme.com"', c: "s" }, { t: "," }],
        [{ t: "  " }, { t: '"active"', c: "k" }, { t: ": " }, { t: "true", c: "s" }],
        [{ t: "}" }],
      ],
    },
  },
];

const STEPS: { title: MKey; body: MKey }[] = [
  { title: "integrationsStep1Title", body: "integrationsStep1Body" },
  { title: "integrationsStep2Title", body: "integrationsStep2Body" },
  { title: "integrationsStep3Title", body: "integrationsStep3Body" },
];

/** Paths are relative to the TENANT's subdomain, which is the issuer — see the section copy. */
const ENDPOINTS: [string, MKey][] = [
  ["/.well-known/openid-configuration", "integrationsEndpointDiscovery"],
  ["/oauth2/authorize", "integrationsEndpointAuthorize"],
  ["/oauth2/token", "integrationsEndpointToken"],
  ["/oauth2/jwks", "integrationsEndpointJwks"],
  ["/userinfo", "integrationsEndpointUserinfo"],
  ["/saml2/metadata", "integrationsEndpointSamlMetadata"],
  ["/saml2/authenticate", "integrationsEndpointSamlSso"],
  ["/saml2/logout", "integrationsEndpointSamlSlo"],
  ["/scim/v2/Users · /scim/v2/Groups", "integrationsEndpointScim"],
];

/** Every one of these presents as "the login just fails", which is why they are worth naming. */
const PITFALLS: [MKey, MKey][] = [
  ["integrationsPitfall1Title", "integrationsPitfall1Body"],
  ["integrationsPitfall2Title", "integrationsPitfall2Body"],
  ["integrationsPitfall3Title", "integrationsPitfall3Body"],
  ["integrationsPitfall4Title", "integrationsPitfall4Body"],
  ["integrationsPitfall5Title", "integrationsPitfall5Body"],
];

const APPS = [
  "Google Workspace", "Slack", "GitHub", "AWS", "Salesforce", "Notion", "Zoom", "Jira",
  "Microsoft 365", "GitLab", "Datadog", "Figma",
];
