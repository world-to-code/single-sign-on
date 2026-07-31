import type { ComponentType } from "react";
import { useTranslation } from "react-i18next";
import {
  Building2, Check, CheckCircle2, Fingerprint, KeyRound, Lock, Minus, RefreshCw,
  ScrollText, ShieldCheck, UsersRound,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Section } from "@/components/marketing/MarketingLayout";
import { AuditSeverityChart } from "@/components/marketing/AuditSeverityChart";

type MKey = keyof (typeof import("@/i18n/en/marketing"))["marketing"];
import { cn } from "@/lib/utils";

/** Product page ("/product"): a capability-by-capability deep dive as alternating feature rows, each
 *  paired with its own purpose-built tokenized mock — no repeated card grid. */
export default function Product() {
  const { t } = useTranslation("marketing");
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <div className="relative mx-auto max-w-3xl px-4 py-20 text-center sm:px-6 sm:py-24">
          <Badge variant="muted" className="mb-5">{t("productHeroBadge")}</Badge>
          <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">{t("productHeroTitle")}</h1>
          <p className="mx-auto mt-5 max-w-xl text-pretty text-lg text-muted-foreground">{t("productHeroBody")}</p>
        </div>
      </section>

      <Section>
        <div className="space-y-20 lg:space-y-28">
          {CAPABILITIES.map((c, i) => (
            <div key={c.title} className="grid items-center gap-8 lg:grid-cols-2 lg:gap-14">
              <div className={cn(i % 2 === 1 && "lg:order-2")}>
                <div className="mb-4 flex size-11 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                  <c.icon className="size-5" />
                </div>
                <h2 className="text-2xl font-semibold tracking-tight">{t(c.title)}</h2>
                <p className="mt-3 text-muted-foreground">{t(c.body)}</p>
                <ul className="mt-5 space-y-2.5">
                  {c.points.map((p) => (
                    <li key={p} className="flex gap-2.5 text-sm">
                      <Check className="mt-0.5 size-4 shrink-0 text-primary" /> <span>{t(p)}</span>
                    </li>
                  ))}
                </ul>
              </div>
              <div className={cn("rounded-xl border bg-muted/30 p-6", i % 2 === 1 && "lg:order-1")}>
                <c.Mock />
              </div>
            </div>
          ))}
        </div>
      </Section>

      <Section tone="muted">
        <div className="mb-8 max-w-2xl">
          <Badge variant="muted" className="mb-3">{t("productSpecBadge")}</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("productSpecTitle")}</h2>
        </div>
        <dl className="grid overflow-hidden rounded-xl border bg-card sm:grid-cols-2 lg:grid-cols-4 [&>div]:border-b [&>div:last-child]:border-b-0 sm:[&>div]:border-b-0 sm:[&>div]:border-r sm:[&>div:nth-child(2n)]:border-r-0 lg:[&>div]:border-r lg:[&>div:last-child]:border-r-0">
          {SPECS.map((s) => (
            <div key={s.label} className="p-6">
              <dt className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t(s.label)}</dt>
              <dd className="mt-1.5 font-medium">{t(s.value)}</dd>
            </div>
          ))}
        </dl>
      </Section>

      <Section>
        <Badge variant="muted" className="mb-4">{t("productRunBadge")}</Badge>
        <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("productRunTitle")}</h2>
        <p className="mt-4 max-w-2xl text-pretty text-muted-foreground">{t("productRunBody")}</p>
        <div className="mt-10 grid gap-4 sm:grid-cols-2">
          {RUNNING.map(([title, body]) => (
            <div key={title} className="rounded-xl border bg-card p-6">
              <h3 className="font-semibold">{t(title)}</h3>
              <p className="mt-2 text-sm text-pretty text-muted-foreground">{t(body)}</p>
            </div>
          ))}
        </div>
      </Section>

      <Section tone="muted">
        <div className="mx-auto max-w-3xl">
          <Badge variant="muted" className="mb-4">{t("productLimitsBadge")}</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("productLimitsTitle")}</h2>
          <p className="mt-4 text-pretty text-muted-foreground">{t("productLimitsBody")}</p>
          <ul className="mt-8 space-y-5">
            {LIMITS.map(([title, body]) => (
              <li key={title} className="border-l-2 border-muted-foreground/30 pl-5">
                <h3 className="font-semibold">{t(title)}</h3>
                <p className="mt-1 text-sm text-pretty text-muted-foreground">{t(body)}</p>
              </li>
            ))}
          </ul>
        </div>
      </Section>

    </>
  );
}

/* ---------- Per-capability mocks (each visually distinct) ---------- */

function SsoMock() {
  const { t } = useTranslation("marketing");
  return (
    <div className="space-y-3">
      {[
        { badge: "OIDC", name: "OpenID Connect", ep: "/oauth2/authorize" },
        { badge: "SAML", name: "SAML 2.0", ep: "/saml2/authenticate" },
      ].map((p) => (
        <div key={p.badge} className="flex items-center justify-between rounded-lg border bg-card px-4 py-3">
          <div className="flex items-center gap-3">
            <span className="flex size-9 items-center justify-center rounded-md bg-accent text-xs font-semibold text-accent-foreground">{p.badge}</span>
            <div>
              <div className="text-sm font-medium">{p.name}</div>
              <div className="font-mono text-xs text-muted-foreground">{p.ep}</div>
            </div>
          </div>
          <CheckCircle2 className="size-4 text-success" />
        </div>
      ))}
      <div className="flex items-center gap-2 rounded-lg border border-dashed px-4 py-2.5 text-xs text-muted-foreground">
        <KeyRound className="size-3.5 text-primary" /> {t("productMockSsoOne")}
      </div>
    </div>
  );
}

function MfaMock() {
  const { t } = useTranslation("marketing");
  return (
    <div className="space-y-3">
      {[
        { icon: Fingerprint, name: t("productMockPasskey"), meta: t("productMockPasskeyMeta"), state: t("productMockPasskeyState") },
        { icon: KeyRound, name: t("productMockAuthApp"), meta: t("productMockAuthAppMeta"), state: t("productMockAuthAppState") },
      ].map((f) => (
        <div key={f.name} className="flex items-center justify-between rounded-lg border bg-card px-4 py-3">
          <div className="flex items-center gap-3">
            <f.icon className="size-5 text-primary" />
            <div>
              <div className="text-sm font-medium">{f.name}</div>
              <div className="text-xs text-muted-foreground">{f.meta}</div>
            </div>
          </div>
          <Badge variant="success">{f.state}</Badge>
        </div>
      ))}
      <div className="flex items-center gap-2 rounded-lg bg-primary/10 px-4 py-2.5 text-xs font-medium text-primary">
        <ShieldCheck className="size-3.5" /> {t("productMockMfaStepup")}
      </div>
    </div>
  );
}

function ScimMock() {
  const { t } = useTranslation("marketing");
  const rows = [
    { user: "jordan@acme.com", state: t("productMockProvisioned"), tone: "success" as const },
    { user: "priya@acme.com", state: t("productMockProvisioned"), tone: "success" as const },
    { user: "sam@acme.com", state: t("productMockDeprovisioned"), tone: "muted" as const },
  ];
  return (
    <div className="rounded-lg border bg-card">
      <div className="flex items-center justify-between border-b px-4 py-2.5">
        <span className="flex items-center gap-2 text-sm font-medium"><RefreshCw className="size-4 text-primary" /> {t("productMockDirectorySync")}</span>
        <span className="inline-flex items-center gap-1.5 text-xs text-success"><span className="size-1.5 rounded-full bg-success" /> {t("productMockLive")}</span>
      </div>
      <ul className="divide-y">
        {rows.map((r) => (
          <li key={r.user} className="flex items-center justify-between px-4 py-2.5">
            <span className="font-mono text-xs text-muted-foreground">{r.user}</span>
            <Badge variant={r.tone}>{r.state}</Badge>
          </li>
        ))}
      </ul>
    </div>
  );
}

function TenantMock() {
  const { t } = useTranslation("marketing");
  const orgs = ["acme.mysso.com", "globex.mysso.com", "initech.mysso.com"];
  return (
    <div className="space-y-2.5">
      {orgs.map((o) => (
        <div key={o} className="flex items-center justify-between rounded-lg border bg-card px-4 py-3">
          <span className="flex items-center gap-2.5">
            <Building2 className="size-4 text-primary" />
            <span className="font-mono text-xs">{o}</span>
          </span>
          <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
            <Lock className="size-3" /> {t("productMockRlsIsolated")}
          </span>
        </div>
      ))}
      <p className="pt-1 text-center text-xs text-muted-foreground">{t("productMockRlsFooter")}</p>
    </div>
  );
}

function RbacMock() {
  const { t } = useTranslation("marketing");
  const caps = [t("productMockCapRead"), t("productMockCapWrite"), t("productMockCapAdmin")];
  const roles: { role: string; grants: boolean[] }[] = [
    { role: t("productMockRoleOrgAdmin"), grants: [true, true, true] },
    { role: t("productMockRoleMember"), grants: [true, true, false] },
    { role: t("productMockRoleAuditor"), grants: [true, false, false] },
  ];
  return (
    <div className="overflow-hidden rounded-lg border bg-card">
      <div className="grid grid-cols-[1.4fr_repeat(3,1fr)] border-b bg-muted/40 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
        <span className="px-3 py-2">{t("productMockColRole")}</span>
        {caps.map((c) => <span key={c} className="px-3 py-2 text-center">{c}</span>)}
      </div>
      {roles.map((r) => (
        <div key={r.role} className="grid grid-cols-[1.4fr_repeat(3,1fr)] items-center border-b last:border-b-0 text-sm">
          <span className="px-3 py-2.5 font-medium">{r.role}</span>
          {r.grants.map((g, i) => (
            <span key={i} className="flex justify-center px-3 py-2.5">
              {g ? <Check className="size-4 text-primary" /> : <Minus className="size-4 text-muted-foreground/40" />}
            </span>
          ))}
        </div>
      ))}
    </div>
  );
}

function AuditMock() {
  const { t } = useTranslation("marketing");
  const events = [
    { t: "09:14", e: "user.login · passkey" },
    { t: "09:12", e: "client.create · admin" },
    { t: "09:07", e: "session.revoke · SLO" },
  ];
  return (
    <div className="space-y-4 rounded-lg border bg-card p-4">
      <AuditSeverityChart
        labels={["M", "T", "W", "T", "F", "S", "S"]}
        legend={[t("productMockSevInfo"), t("productMockSevWarning"), t("productMockSevCritical")]}
        className="text-foreground"
      />
      <ul className="space-y-1.5">
        {events.map((ev) => (
          <li key={ev.t} className="flex items-center gap-3 font-mono text-xs">
            <span className="text-muted-foreground">{ev.t}</span>
            <span className="truncate">{ev.e}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

const CAPABILITIES: { icon: LucideIcon; title: MKey; body: MKey; points: MKey[]; Mock: ComponentType }[] = [
  {
    icon: KeyRound, title: "productSsoTitle", Mock: SsoMock, body: "productSsoBody",
    points: ["productSsoPoint1", "productSsoPoint2", "productSsoPoint3", "productSsoPoint4"],
  },
  {
    icon: Fingerprint, title: "productMfaTitle", Mock: MfaMock, body: "productMfaBody",
    points: ["productMfaPoint1", "productMfaPoint2", "productMfaPoint3", "productMfaPoint4"],
  },
  {
    icon: RefreshCw, title: "productScimTitle", Mock: ScimMock, body: "productScimBody",
    points: ["productScimPoint1", "productScimPoint2", "productScimPoint3", "productScimPoint4"],
  },
  {
    icon: Building2, title: "productTenantTitle", Mock: TenantMock, body: "productTenantBody",
    points: ["productTenantPoint1", "productTenantPoint2", "productTenantPoint3", "productTenantPoint4"],
  },
  {
    icon: UsersRound, title: "productRbacTitle", Mock: RbacMock, body: "productRbacBody",
    points: ["productRbacPoint1", "productRbacPoint2", "productRbacPoint3", "productRbacPoint4"],
  },
  {
    icon: ScrollText, title: "productAuditTitle", Mock: AuditMock, body: "productAuditBody",
    points: ["productAuditPoint1", "productAuditPoint2", "productAuditPoint3", "productAuditPoint4"],
  },
];

/** The operational shape of self-hosting, which decides the answer as much as the capability list does. */
const RUNNING: [MKey, MKey][] = [
  ["productRunStackTitle", "productRunStackBody"],
  ["productRunDataTitle", "productRunDataBody"],
  ["productRunDnsTitle", "productRunDnsBody"],
  ["productRunOpsTitle", "productRunOpsBody"],
];

/** Where something else is the better answer. A page that never says no gives a reader nothing to trust. */
const LIMITS: [MKey, MKey][] = [
  ["productLimit1Title", "productLimit1Body"],
  ["productLimit2Title", "productLimit2Body"],
  ["productLimit3Title", "productLimit3Body"],
  ["productLimit4Title", "productLimit4Body"],
];

const SPECS: { label: MKey; value: MKey }[] = [
  { label: "productSpecProtocolsLabel", value: "productSpecProtocolsValue" },
  { label: "productSpecFactorsLabel", value: "productSpecFactorsValue" },
  { label: "productSpecProvisioningLabel", value: "productSpecProvisioningValue" },
  { label: "productSpecIsolationLabel", value: "productSpecIsolationValue" },
];
